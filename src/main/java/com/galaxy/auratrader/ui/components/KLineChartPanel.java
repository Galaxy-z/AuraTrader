package com.galaxy.auratrader.ui.components;

import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.PositionInformationV3ResponseInner;
import com.galaxy.auratrader.model.DataPool;
import com.galaxy.auratrader.model.KlineData;
import com.galaxy.auratrader.service.BinanceService;
import com.galaxy.auratrader.service.IndicatorService;
import com.galaxy.auratrader.service.IndicatorService.IndicatorPoint;
import com.galaxy.auratrader.service.IndicatorService.IndicatorResult;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartMouseEvent;
import org.jfree.chart.ChartMouseListener;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.AxisLocation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.chart.renderer.xy.StandardXYItemRenderer;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.chart.renderer.xy.XYItemRendererState;
import org.jfree.chart.plot.PlotRenderingInfo;
import org.jfree.chart.plot.CrosshairState;
import org.jfree.data.Range;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.jfree.data.xy.DefaultHighLowDataset;
import org.jfree.data.xy.OHLCDataset;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.Toolkit;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.jfree.chart.event.AxisChangeEvent;
import org.jfree.chart.event.AxisChangeListener;

@Slf4j
public class KLineChartPanel extends JPanel {

    private final ChartPanel chartPanel;
    private List<KlineData> currentData = new ArrayList<>();
    private IndicatorResult indicators = null;
    private List<PositionInformationV3ResponseInner> positions = new ArrayList<>();
    private List<com.binance.connector.client.derivatives_trading_usds_futures.rest.model.AllOrdersResponseInner> orders = new ArrayList<>();

    // --- New state for panning / animation ---
    private boolean userPanning = false;
    private int lastMouseX = -1;
    private boolean isAnimating = false;
    private javax.swing.Timer animationTimer = null;
    private Range animationStartRange = null;
    private Range animationTargetRange = null;
    private long animationStartTime = 0L;
    private int animationDurationMs = 300; // default animation duration
    private boolean userMovedFromRightEdge = false; // whether user panned away from right edge
    // --- end new state ---

    // AWT listener to intercept mouse events targeted to chartPanel
    private AWTEventListener chartAwtListener = null;
    // domain axis change listener used to recompute Y axis when visible domain changes
    private AxisChangeListener domainAxisListener = null;
    private ValueAxis lastDomainAxis = null;

    // Service hookup (set by MainFrame) so panel can request earlier klines when user pans to left edge
    private BinanceService binanceService = null;
    private volatile boolean loadingEarlier = false;
    private long lastEarlierLoadTime = 0L;
    private int earlierLoadCount = 200; // default number of candles to fetch when loading earlier data

    // Visible loading indicator shown when fetching earlier data
    private final JLabel loadingLabel = new JLabel("正在加载更早数据...");

    public void setBinanceService(BinanceService service) {
        this.binanceService = service;
    }

    public KLineChartPanel() {
        setLayout(new BorderLayout());
        // Initial empty chart
        JFreeChart chart = createChart(null, "No Data");
        // Use an anonymous ChartPanel subclass to draw a sliding latest-price label on top of the chart
        chartPanel = new ChartPanel(chart) {
            @Override
            public void paintComponent(Graphics g) {
                // Ensure any internal zoom rectangle is cleared before ChartPanel draws it
                clearChartPanelZoomRectangle(this);
                super.paintComponent(g);
                clearChartPanelZoomRectangle(this);

                // Draw the moving price label only when we have data
                if (currentData == null || currentData.isEmpty()) {
                    return;
                }

                try {
                    Plot plot = getChart().getPlot();
                    XYPlot mainPlot;
                    if (plot instanceof CombinedDomainXYPlot) {
                        CombinedDomainXYPlot combined = (CombinedDomainXYPlot) plot;
                        mainPlot = (XYPlot) combined.getSubplots().get(0);
                    } else {
                        mainPlot = (XYPlot) plot;
                    }
                    NumberAxis rangeAxis = (NumberAxis) mainPlot.getRangeAxis();

                    // latest price (use last close)
                    double latestPrice = currentData.get(currentData.size() - 1).getClose().doubleValue();

                    // Plot area in Java2D coordinates
                    Rectangle2D plotArea = getScreenDataArea();
                    if (plotArea == null) {
                        return;
                    }

                    // Get the correct data area for the main plot
                    Rectangle2D dataArea;
                    if (plot instanceof CombinedDomainXYPlot) {
                        // For combined plot, main plot takes 3/5 of the height (weights 3,1,1)
                        double height = plotArea.getHeight() * 3 / 5;
                        dataArea = new Rectangle2D.Double(plotArea.getMinX(), plotArea.getMinY(), plotArea.getWidth(), height);
                    } else {
                        dataArea = plotArea;
                    }

                    // --- 持仓虚线绘制 ---
                    if (positions != null && !positions.isEmpty()) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        try {
                            double minPrice = rangeAxis.getRange().getLowerBound();
                            double maxPrice = rangeAxis.getRange().getUpperBound();
                            double yMin = rangeAxis.valueToJava2D(minPrice, dataArea, mainPlot.getRangeAxisEdge());
                            double yMax = rangeAxis.valueToJava2D(maxPrice, dataArea, mainPlot.getRangeAxisEdge());
                            for (PositionInformationV3ResponseInner pos : positions) {
                                if (pos == null) continue;
                                double entryPrice = 0.0;
                                try {
                                    entryPrice = Double.parseDouble(pos.getEntryPrice());
                                } catch (Exception ignore) {
                                }
                                if (entryPrice <= 0) continue;
                                String side = pos.getPositionSide();
                                if (side == null) side = "BOTH";
                                Color lineColor = Color.GRAY;
                                if ("LONG".equalsIgnoreCase(side)) lineColor = Color.GREEN;
                                else if ("SHORT".equalsIgnoreCase(side)) lineColor = Color.RED;
                                // 判断虚线y坐标
                                double yEntry;
                                boolean outOfRange = false;
                                String label;
                                if (entryPrice < minPrice) {
                                    yEntry = yMin;
                                    outOfRange = true;
                                    label = "↓ " + String.format("%.2f", entryPrice);
                                } else if (entryPrice > maxPrice) {
                                    yEntry = yMax;
                                    outOfRange = true;
                                    label = "↑ " + String.format("%.2f", entryPrice);
                                } else {
                                    yEntry = rangeAxis.valueToJava2D(entryPrice, dataArea, mainPlot.getRangeAxisEdge());
                                    label = String.format("%.2f", entryPrice);
                                }
                                // 画虚线
                                Stroke oldStroke = g2.getStroke();
                                g2.setColor(lineColor);
                                g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{8, 8}, 0));
                                g2.drawLine((int) dataArea.getMinX(), (int) yEntry, (int) dataArea.getMaxX(), (int) yEntry);
                                g2.setStroke(oldStroke);
                                // 在最左侧标注价格（绿色/红色背景，白色字）
                                // 计算盈亏额和回报率
                                double positionAmt = 0.0, initialMargin = 0.0;
                                try {
                                    positionAmt = Double.parseDouble(pos.getPositionAmt());
                                } catch (Exception ignore) {
                                }
                                try {
                                    initialMargin = Double.parseDouble(pos.getInitialMargin());
                                } catch (Exception ignore) {
                                }

                                double profit = 0.0;
                                double absAmt = Math.abs(positionAmt);
                                if ("LONG".equalsIgnoreCase(side)) {
                                    profit = (latestPrice - entryPrice) * absAmt;
                                } else if ("SHORT".equalsIgnoreCase(side)) {
                                    profit = (entryPrice - latestPrice) * absAmt;
                                }
                                double roi = initialMargin != 0.0 ? profit / initialMargin : 0.0;
                                // 获取成本币种
                                String marginAsset = pos.getMarginAsset();
                                if (marginAsset == null || marginAsset.isEmpty()) marginAsset = "";
                                String profitStr = String.format("%.2f %s", profit, marginAsset);
                                String roiStr = String.format("%.2f%%", roi * 100);
                                String infoLabel = label + "  " + profitStr + " / " + roiStr;
                                // 盈亏颜色
                                Color infoBg = profit >= 0 ? new Color(0, 180, 0) : new Color(220, 0, 0);
                                // 标注区域
                                Font font = g2.getFont().deriveFont(Font.BOLD, 12f);
                                g2.setFont(font);
                                FontMetrics fm = g2.getFontMetrics(font);
                                int infoWidth = fm.stringWidth(infoLabel);
                                int infoHeight = fm.getHeight();
                                int x = (int) (dataArea.getMinX() + 4);
                                int y = (int) yEntry - 2;
                                if (y < dataArea.getMinY() + infoHeight) y = (int) (dataArea.getMinY() + infoHeight);
                                if (y > dataArea.getMaxY() - 2) y = (int) (dataArea.getMaxY() - 2);
                                int arc = 8;
                                int padX = 6, padY = 2;
                                int rectW = infoWidth + padX * 2;
                                int rectH = infoHeight;
                                int rectX = x - padX;
                                int rectY = y - fm.getAscent() - padY;
                                g2.setColor(infoBg);
                                g2.fillRoundRect(rectX, rectY, rectW, rectH, arc, arc);
                                g2.setColor(Color.WHITE);
                                g2.drawString(infoLabel, x, y);
                            }
                        } finally {
                            g2.dispose();
                        }
                    }
                    // --- 持仓虚线绘制结束 ---

                    // --- 订单箭头绘制 ---
                    if (orders != null && !orders.isEmpty()) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        try {
                            double minPrice = rangeAxis.getRange().getLowerBound();
                            double maxPrice = rangeAxis.getRange().getUpperBound();
                            double yMin = rangeAxis.valueToJava2D(minPrice, dataArea, mainPlot.getRangeAxisEdge());
                            double yMax = rangeAxis.valueToJava2D(maxPrice, dataArea, mainPlot.getRangeAxisEdge());
                            int arrowSize = 6;
                            for (com.binance.connector.client.derivatives_trading_usds_futures.rest.model.AllOrdersResponseInner order : orders) {
                                if (order == null || !"FILLED".equals(order.getStatus())) continue;
                                String avgPriceStr = order.getAvgPrice();
                                if (avgPriceStr == null || avgPriceStr.isEmpty()) continue;
                                double avgPrice = 0.0;
                                try {
                                    avgPrice = Double.parseDouble(avgPriceStr);
                                } catch (Exception ignore) {
                                    continue;
                                }
                                if (avgPrice <= 0) continue;
                                String side = order.getSide();
                                String positionSide = order.getPositionSide();
                                if (side == null || positionSide == null) continue;
                                boolean isBuy = "BUY".equalsIgnoreCase(side);
                                boolean isLong = "LONG".equalsIgnoreCase(positionSide);
                                Color arrowColor = isLong ? Color.GREEN : Color.RED;
                                // 计算x坐标（时间轴）
                                double timeValue = order.getTime();
                                double xArrow = mainPlot.getDomainAxis().valueToJava2D(timeValue, dataArea, mainPlot.getDomainAxisEdge());
                                // 计算y坐标
                                double yArrow = rangeAxis.valueToJava2D(avgPrice, dataArea, mainPlot.getRangeAxisEdge());
                                // 检查是否在显示区域内
                                if (xArrow < dataArea.getMinX() || xArrow > dataArea.getMaxX() || avgPrice < minPrice || avgPrice > maxPrice)
                                    continue;
                                // 绘制箭头
                                g2.setColor(arrowColor);
                                Polygon arrow = new Polygon();
                                if (isBuy) {
                                    // 向上箭头
                                    arrow.addPoint((int) xArrow, (int) yArrow + arrowSize);
                                    arrow.addPoint((int) xArrow - arrowSize, (int) yArrow - arrowSize);
                                    arrow.addPoint((int) xArrow + arrowSize, (int) yArrow - arrowSize);
                                } else {
                                    // 向下箭头
                                    arrow.addPoint((int) xArrow, (int) yArrow - arrowSize);
                                    arrow.addPoint((int) xArrow - arrowSize, (int) yArrow + arrowSize);
                                    arrow.addPoint((int) xArrow + arrowSize, (int) yArrow + arrowSize);
                                }
                                g2.fillPolygon(arrow);
                                g2.setColor(Color.BLACK);
                                g2.drawPolygon(arrow);
                            }
                        } finally {
                            g2.dispose();
                        }
                    }
                    // --- 订单箭头绘制结束 ---

                    // Convert latest price value to Java2D y coordinate and handle out-of-range by placing label at top/bottom
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        // Prepare label text
                        String text = String.format("%.2f", latestPrice);

                        // Determine if latestPrice is outside current axis range
                        double minPrice = rangeAxis.getRange().getLowerBound();
                        double maxPrice = rangeAxis.getRange().getUpperBound();
                        double yForMin = rangeAxis.valueToJava2D(minPrice, dataArea, mainPlot.getRangeAxisEdge());
                        double yForMax = rangeAxis.valueToJava2D(maxPrice, dataArea, mainPlot.getRangeAxisEdge());

                        // Label dimensions and position (anchored to right)
                        int padding = 6;
                        Font font = g2.getFont().deriveFont(Font.BOLD, 12f);
                        g2.setFont(font);
                        FontMetrics fm = g2.getFontMetrics(font);
                        int textWidth = fm.stringWidth(text);
                        int textHeight = fm.getHeight();

                        int rectWidth = textWidth + padding * 2;
                        int rectHeight = textHeight;

                        int x = (int) dataArea.getMaxX() + 5; // Position on the axis area

                        // Compute y: if price is above/below axis, snap to top/bottom of Y axis
                        int minY = (int) Math.ceil(dataArea.getMinY());
                        int maxY = (int) Math.floor(dataArea.getMaxY());
                        int y;
                        if (latestPrice > maxPrice) {
                            // place at top of axis
                            y = (int) Math.round(yForMax) - rectHeight / 2;
                            text = "↑ " + text;
                        } else if (latestPrice < minPrice) {
                            // place at bottom of axis
                            y = (int) Math.round(yForMin) - rectHeight / 2;
                            text = "↓ " + text;
                        } else {
                            double yJava2D = rangeAxis.valueToJava2D(latestPrice, dataArea, mainPlot.getRangeAxisEdge());
                            y = (int) Math.round(yJava2D) - rectHeight / 2;
                        }

                        if (y < minY) y = minY;
                        if (y + rectHeight > maxY) y = maxY - rectHeight;

                        // Draw semi-transparent rounded rectangle
                        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
                        g2.setColor(new Color(0x33, 0x33, 0x33));
                        g2.fillRoundRect(x, y, rectWidth, rectHeight, 6, 6);

                        // Draw border
                        g2.setColor(Color.DARK_GRAY);
                        g2.drawRoundRect(x, y, rectWidth, rectHeight, 6, 6);

                        // Draw price text (white)
                        g2.setColor(Color.WHITE);
                        int textX = x + padding;
                        int textY = y + fm.getAscent();
                        g2.drawString(text, textX, textY);

                    } finally {
                        g2.dispose();
                    }
                } catch (Exception ex) {
                    // Don't let drawing errors break the UI; ignore
                }
            }
        };

        // Ensure built-in handlers are disabled (ChartPanel can re-install handlers when chart changes)
        disableChartPanelBoxZoom();

        // Re-apply disable when ChartPanel becomes showing (some JFreeChart versions re-install listeners)
        chartPanel.addHierarchyListener(evt -> {
            if ((evt.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && chartPanel.isShowing()) {
                disableChartPanelBoxZoom();
            }
        });

        // --- Loading indicator setup ---
        try {
            loadingLabel.setOpaque(true);
            loadingLabel.setBackground(new Color(0, 0, 0, 180));
            loadingLabel.setForeground(Color.WHITE);
            loadingLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            loadingLabel.setVisible(false);
            loadingLabel.setFont(loadingLabel.getFont().deriveFont(Font.BOLD, 12f));
            // place label on chartPanel so it overlays nicely
            chartPanel.setLayout(null);
            chartPanel.add(loadingLabel);
            // position label when chartPanel resizes
            chartPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    SwingUtilities.invokeLater(() -> positionLoadingLabel());
                }

                @Override
                public void componentShown(java.awt.event.ComponentEvent e) {
                    SwingUtilities.invokeLater(() -> positionLoadingLabel());
                }
            });
            // initial position
            positionLoadingLabel();
        } catch (Throwable ignore) {
        }

        add(chartPanel, BorderLayout.CENTER);

        chartPanel.addChartMouseListener(new ChartMouseListener() {
            @Override
            public void chartMouseClicked(ChartMouseEvent event) {
                // Do nothing
            }

            @Override
            public void chartMouseMoved(ChartMouseEvent event) {
                JFreeChart chart = event.getChart();
                XYPlot plot = (XYPlot) chart.getPlot();
                Point2D p = chartPanel.translateScreenToJava2D(event.getTrigger().getPoint());
                Rectangle2D plotArea = chartPanel.getScreenDataArea();
                double domainValue = plot.getDomainAxis().java2DToValue(p.getX(), plotArea, plot.getDomainAxisEdge());
                long time = (long) domainValue;
                int closestIndex = findClosestIndex(currentData, time);
                if (closestIndex >= 0) {
                    KlineData data = currentData.get(closestIndex);
                    String tooltip = formatTooltip(data);
                    chartPanel.setToolTipText(tooltip);
                } else {
                    chartPanel.setToolTipText(null);
                }
            }
        });
    }

    private void disableChartPanelBoxZoom() {
        try {
            // Remove existing mouse listeners/motion/wheel that ChartPanel may install when chart changes
            for (java.awt.event.MouseListener ml : chartPanel.getMouseListeners()) {
                chartPanel.removeMouseListener(ml);
            }
            for (java.awt.event.MouseMotionListener mml : chartPanel.getMouseMotionListeners()) {
                chartPanel.removeMouseMotionListener(mml);
            }
            for (java.awt.event.MouseWheelListener mwl : chartPanel.getMouseWheelListeners()) {
                chartPanel.removeMouseWheelListener(mwl);
            }
        } catch (Throwable ignored) {
        }
        try {
            chartPanel.setMouseZoomable(false);
            chartPanel.setDomainZoomable(false);
            chartPanel.setRangeZoomable(false);
        } catch (Throwable ignored) {
        }
        try {
            chartPanel.setZoomTriggerDistance(Integer.MAX_VALUE);
        } catch (Throwable ignore) {
            // ignore if not supported
        }
        clearChartPanelZoomRectangle(chartPanel);
    }

    public void setIndicators(IndicatorResult indicators) {
        // Save current domain axis range
        Range currentDomainRange = getCurrentDomainRange();

        this.indicators = indicators;
        if (!currentData.isEmpty()) {
            DefaultHighLowDataset dataset = createDataset(currentData, chartPanel.getChart().getTitle().getText());
            JFreeChart chart = createChart(dataset, chartPanel.getChart().getTitle().getText());
            chartPanel.setChart(chart);

            // Ensure ChartPanel's built-in handlers/zoom are disabled after setting a new chart
            disableChartPanelBoxZoom();

            // Restore domain axis range
            if (currentDomainRange != null) {
                setDomainRange(currentDomainRange);
            }
            // Ensure Y-axis updated for visible data
            updateRangeAxisForVisibleData();
        }
    }

    public void updateData(List<KlineData> klineDataList, String title) {
        // Save current domain axis range
        Range currentDomainRange = getCurrentDomainRange();

        this.currentData = new ArrayList<>(klineDataList);
        DefaultHighLowDataset dataset = createDataset(currentData, title);
        JFreeChart chart = createChart(dataset, title);
        chartPanel.setChart(chart);

        // Ensure ChartPanel's built-in handlers/zoom are disabled after setting a new chart
        disableChartPanelBoxZoom();

        // Restore domain axis range
        if (currentDomainRange != null) {
            setDomainRange(currentDomainRange);
        }
        // Update Y-axis to fit visible data
        updateRangeAxisForVisibleData();
    }

    public void updateLatestKline(KlineData latestKline) {
        // Save current domain axis range
        Range currentDomainRange = getCurrentDomainRange();

        boolean appended = false;
        if (currentData.isEmpty()) {
            currentData.add(latestKline);
            appended = true;
        } else {
            KlineData last = currentData.get(currentData.size() - 1);
            if (last.getOpenTime().equals(latestKline.getOpenTime())) {
                // Update the last one
                currentData.set(currentData.size() - 1, latestKline);
            } else {
                // Append new
                currentData.add(latestKline);
                appended = true;
            }
        }
        // Update chart
        DefaultHighLowDataset dataset = createDataset(currentData, chartPanel.getChart().getTitle().getText());
        JFreeChart chart = createChart(dataset, chartPanel.getChart().getTitle().getText());
        chartPanel.setChart(chart);

        // Ensure ChartPanel's built-in handlers/zoom are disabled after setting a new chart
        disableChartPanelBoxZoom();

        // If we appended and user hasn't panned away, animate domain to follow new data
        try {
            if (appended && !userMovedFromRightEdge && isAtRightEdge()) {
                Range current = getCurrentDomainRange();
                if (current != null) {
                    double length = current.getUpperBound() - current.getLowerBound();
                    long latestTime = latestKline.getOpenTime().getTime();
                    double targetUpper = latestTime;
                    double targetLower = targetUpper - length;
                    Range dataRange = getDataTimeRange();
                    if (dataRange != null) {
                        if (targetLower < dataRange.getLowerBound()) targetLower = dataRange.getLowerBound();
                        if (targetUpper > dataRange.getUpperBound()) targetUpper = dataRange.getUpperBound();
                    }
                    animateDomainTo(new Range(targetLower, targetUpper), 300);
                }
            }
        } catch (Exception ex) {
            // ignore
        }

        // Restore domain axis range (if no animation was requested)
        if (currentDomainRange != null && !isAnimating) {
            setDomainRange(currentDomainRange);
        }

        // Repaint so the moving price label updates
        chartPanel.repaint();
    }

    public void setPositions(List<PositionInformationV3ResponseInner> positions) {
        this.positions = positions != null ? positions : new ArrayList<>();
        repaint();
    }

    public void setOrders(List<com.binance.connector.client.derivatives_trading_usds_futures.rest.model.AllOrdersResponseInner> orders) {
        this.orders = orders != null ? orders : new ArrayList<>();
        repaint();
    }

    private void installDomainPanHandlers() {
        // Click-and-drag (left button) to pan the domain axis
        chartPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    userPanning = true;
                    lastMouseX = e.getX();
                    cancelAnimation();
                    clearChartPanelZoomRectangle(chartPanel);
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (userPanning) {
                    userPanning = false;
                    lastMouseX = -1;
                    // If user released and view is no longer at right edge, mark it
                    userMovedFromRightEdge = !isAtRightEdge();
                    clearChartPanelZoomRectangle(chartPanel);
                }
            }
        });

        chartPanel.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (userPanning) {
                    int x = e.getX();
                    int dx = x - lastMouseX;
                    // Pan the domain by the pixel delta (invert sign for intuitive drag)
                    // invert pan direction: positive dx (mouse moved right) should pan the domain to the right
                    panDomainByPixels(dx);
                    lastMouseX = x;
                }
                // ensure ChartPanel's internal zoom rectangle (if any) is cleared immediately
                clearChartPanelZoomRectangle(chartPanel);
            }
        });
    }

    private void clearChartPanelZoomRectangle(ChartPanel cp) {
        try {
            Field f = ChartPanel.class.getDeclaredField("zoomRectangle");
            f.setAccessible(true);
            Object zr = f.get(cp);
            if (zr != null) {
                f.set(cp, null);
                cp.repaint();
            }
        } catch (Throwable ignore) {
            // ignore if field doesn't exist or inaccessible
        }
    }

    private void panDomainByPixels(int dx) {
        try {
            JFreeChart chart = chartPanel.getChart();
            Plot plot = chart.getPlot();
            XYPlot mainPlot;
            if (plot instanceof CombinedDomainXYPlot) {
                mainPlot = (XYPlot) ((CombinedDomainXYPlot) plot).getSubplots().get(0);
            } else {
                mainPlot = (XYPlot) plot;
            }
            ValueAxis domainAxis = mainPlot.getDomainAxis();
            Rectangle2D dataArea = chartPanel.getScreenDataArea();
            if (dataArea == null) return;

            // map center x and shifted x to domain values and compute delta
            double centerX = dataArea.getCenterX();
            double v1 = domainAxis.java2DToValue(centerX, dataArea, mainPlot.getDomainAxisEdge());
            double v2 = domainAxis.java2DToValue(centerX + dx, dataArea, mainPlot.getDomainAxisEdge());
            double delta = v1 - v2;

            Range r = domainAxis.getRange();
            double newLower = r.getLowerBound() + delta;
            double newUpper = r.getUpperBound() + delta;

            // Clamp to data range if available
            Range dataRange = getDataTimeRange();
            if (dataRange != null) {
                double dataLower = dataRange.getLowerBound();
                double dataUpper = dataRange.getUpperBound();
                double padding = (r.getUpperBound() - r.getLowerBound()) * 0.05; // 5% padding
                if (newLower < dataLower) {
                    newLower = dataLower;
                    newUpper = newLower + r.getLength();
                }
                if (newUpper > dataUpper + padding) {
                    newUpper = dataUpper + padding;
                    newLower = newUpper - r.getLength();
                }
            }

            domainAxis.setRange(newLower, newUpper);
            chartPanel.repaint();

            // After panning, check whether we are near the left edge and need to load earlier candles
            checkAndLoadEarlierIfNeeded();
        } catch (Exception ex) {
            // ignore
        }
    }

    private void checkAndLoadEarlierIfNeeded() {
        try {
            if (binanceService == null) return;
            if (loadingEarlier) return;
            Range current = getCurrentDomainRange();
            Range dataRange = getDataTimeRange();
            if (current == null || dataRange == null) return;

            double currentLower = current.getLowerBound();
            double dataLower = dataRange.getLowerBound();
            double viewLen = current.getUpperBound() - currentLower;
            if (viewLen <= 0) return;

            double distance = currentLower - dataLower; // how far from absolute left edge
            // If within 2% of the current view length to the left edge, trigger load
            if (distance <= viewLen * 0.02 && (System.currentTimeMillis() - lastEarlierLoadTime > 2000)) {
                loadingEarlier = true;
                lastEarlierLoadTime = System.currentTimeMillis();
                // show visible loading indicator
                setLoadingIndicator(true);
                String symbol = DataPool.getInstance().getCurrentPair();
                String interval = DataPool.getInstance().getCurrentInterval();
                SwingWorker<List<KlineData>, Void> worker = new SwingWorker<>() {
                    @Override
                    protected List<KlineData> doInBackground() {
                        try {
                            return binanceService.fetchEarlierKlines(symbol, interval, earlierLoadCount);
                        } catch (Throwable ex) {
                            log.warn("Failed to fetch earlier klines", ex);
                            return null;
                        }
                    }

                    @Override
                    protected void done() {
                        try {
                            // DataPool will be updated by service; UI will refresh via observer callbacks
                            List<KlineData> fetched = get();
                            if (fetched != null && !fetched.isEmpty()) {
                                // nothing extra required here: MainFrame.onDataUpdated will call chartPanel.updateData and restore domain range
                            }
                        } catch (Exception ignore) {
                        } finally {
                            loadingEarlier = false;
                            // hide visible loading indicator
                            setLoadingIndicator(false);
                        }
                    }
                };
                worker.execute();
            }
        } catch (Throwable ignore) {
        }
    }

    private boolean isAtRightEdge() {
        try {
            Range current = getCurrentDomainRange();
            Range dataRange = getDataTimeRange();
            if (current == null || dataRange == null) return true;
            double currentUpper = current.getUpperBound();
            double dataUpper = dataRange.getUpperBound();
            double tolerance = (current.getUpperBound() - current.getLowerBound()) * 0.1; // 10% tolerance
            return currentUpper >= dataUpper - tolerance;
        } catch (Exception ex) {
            return true;
        }
    }

    private void cancelAnimation() {
        try {
            if (animationTimer != null && animationTimer.isRunning()) {
                animationTimer.stop();
            }
        } catch (Exception ignored) {
        }
        isAnimating = false;
    }

    private void animateDomainTo(Range target, int durationMs) {
        try {
            cancelAnimation();
            JFreeChart chart = chartPanel.getChart();
            if (chart == null) return;
            Plot plot = chart.getPlot();
            XYPlot mainPlot;
            if (plot instanceof CombinedDomainXYPlot) {
                mainPlot = (XYPlot) ((CombinedDomainXYPlot) plot).getSubplots().get(0);
            } else {
                mainPlot = (XYPlot) plot;
            }
            ValueAxis domainAxis = mainPlot.getDomainAxis();
            Range start = domainAxis.getRange();
            if (start == null) return;

            animationStartRange = start;
            animationTargetRange = target;
            animationStartTime = System.currentTimeMillis();
            animationDurationMs = Math.max(50, durationMs);
            isAnimating = true;

            animationTimer = new javax.swing.Timer(20, ae -> {
                long now = System.currentTimeMillis();
                double t = (double) (now - animationStartTime) / animationDurationMs;
                if (t >= 1.0) t = 1.0;
                // ease-out interpolation (quadratic)
                double p = 1 - Math.pow(1 - t, 2);

                double lower = animationStartRange.getLowerBound() + (animationTargetRange.getLowerBound() - animationStartRange.getLowerBound()) * p;
                double upper = animationStartRange.getUpperBound() + (animationTargetRange.getUpperBound() - animationStartRange.getUpperBound()) * p;

                domainAxis.setRange(lower, upper);
                chartPanel.repaint();

                if (t >= 1.0) {
                    animationTimer.stop();
                    isAnimating = false;
                }
            });
            animationTimer.setRepeats(true);
            animationTimer.start();
        } catch (Exception ex) {
            // ignore
            isAnimating = false;
        }
    }

    private DefaultHighLowDataset createDataset(List<KlineData> klineDataList, String title) {
        if (klineDataList == null || klineDataList.isEmpty()) {
            return null;
        }

        int size = klineDataList.size();
        Date[] date = new Date[size];
        double[] high = new double[size];
        double[] low = new double[size];
        double[] open = new double[size];
        double[] close = new double[size];
        double[] volume = new double[size];

        for (int i = 0; i < size; i++) {
            KlineData data = klineDataList.get(i);
            date[i] = data.getOpenTime();
            high[i] = data.getHigh().doubleValue();
            low[i] = data.getLow().doubleValue();
            open[i] = data.getOpen().doubleValue();
            close[i] = data.getClose().doubleValue();
            volume[i] = data.getVolume().doubleValue();
        }

        return new DefaultHighLowDataset(title, date, high, low, open, close, volume);
    }

    private JFreeChart createChart(DefaultHighLowDataset dataset, String title) {
        // create main candle chart first
        JFreeChart candleChart = ChartFactory.createCandlestickChart(
                title,
                "Time",
                "Price",
                dataset,
                false
        );

        XYPlot mainPlot = (XYPlot) candleChart.getPlot();
        mainPlot.setBackgroundPaint(Color.WHITE);
        mainPlot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        mainPlot.setRangeGridlinePaint(Color.LIGHT_GRAY);

        NumberAxis rangeAxis = (NumberAxis) mainPlot.getRangeAxis();
        rangeAxis.setAutoRangeIncludesZero(false);
        mainPlot.setRangeAxisLocation(AxisLocation.TOP_OR_RIGHT);

        CandlestickRenderer renderer = new CustomCandlestickRenderer();
        renderer.setDrawVolume(true);
        renderer.setUpPaint(Color.GREEN);
        renderer.setDownPaint(Color.RED);
        renderer.setUseOutlinePaint(true); // enable outline paint usage
        mainPlot.setRenderer(renderer);

        // attach a domain axis change listener to auto-update Y-axis when domain (visible time range) changes
        try {
            ValueAxis domainAxis = mainPlot.getDomainAxis();
            // remove previous listener if any
            if (domainAxisListener == null) {
                domainAxisListener = new AxisChangeListener() {
                    @Override
                    public void axisChanged(AxisChangeEvent event) {
                        // Recompute Y-axis for visible data
                        updateRangeAxisForVisibleData();
                    }
                };
            }
            // detach from lastDomainAxis if different
            if (lastDomainAxis != null && lastDomainAxis != domainAxis) {
                try { lastDomainAxis.removeChangeListener(domainAxisListener); } catch (Throwable ignore) {}
                lastDomainAxis = null;
            }
            if (lastDomainAxis == null) {
                domainAxis.addChangeListener(domainAxisListener);
                lastDomainAxis = domainAxis;
            }
        } catch (Throwable ignore) {
        }
        // ensure initial Y axis matches visible data
        updateRangeAxisForVisibleData();


        if (indicators != null) {
            try {
                // MA overlay
                TimeSeries maTs = new TimeSeries("MA");
                for (IndicatorPoint p : indicators.getMa()) {
                    if (!Double.isNaN(p.getValue())) {
                        maTs.addOrUpdate(new Millisecond(p.getTime()), p.getValue());
                    }
                }
                TimeSeriesCollection maCol = new TimeSeriesCollection();
                maCol.addSeries(maTs);
                StandardXYItemRenderer maRenderer = new StandardXYItemRenderer();
                maRenderer.setSeriesPaint(0, Color.BLUE);
                mainPlot.setDataset(1, maCol);
                mainPlot.setRenderer(1, maRenderer);

                // Build combined plot sharing domain axis
                CombinedDomainXYPlot combined = new CombinedDomainXYPlot(mainPlot.getDomainAxis());
                combined.add(mainPlot, 3);

                // RSI subplot
                TimeSeries rsiTs = new TimeSeries("RSI");
                for (IndicatorPoint p : indicators.getRsi()) {
                    if (!Double.isNaN(p.getValue())) {
                        rsiTs.addOrUpdate(new Millisecond(p.getTime()), p.getValue());
                    }
                }
                TimeSeriesCollection rsiCol = new TimeSeriesCollection();
                rsiCol.addSeries(rsiTs);
                NumberAxis rsiAxis = new NumberAxis("RSI");
                rsiAxis.setAutoRangeIncludesZero(false);
                rsiAxis.setRange(0.0, 100.0);
                XYPlot rsiPlot = new XYPlot(rsiCol, null, rsiAxis, new StandardXYItemRenderer());
                combined.add(rsiPlot, 1);

                // MACD subplot (macd + signal + hist)
                TimeSeries macdTs = new TimeSeries("MACD");
                TimeSeries macdSigTs = new TimeSeries("MACD Signal");
                TimeSeries macdHistTs = new TimeSeries("MACD Hist");
                for (int i = 0; i < indicators.getMacd().size(); i++) {
                    IndicatorPoint p = indicators.getMacd().get(i);
                    IndicatorPoint s = indicators.getMacdSignal().get(i);
                    IndicatorPoint h = indicators.getMacdHistogram().get(i);
                    macdTs.addOrUpdate(new Millisecond(p.getTime()), p.getValue());
                    macdSigTs.addOrUpdate(new Millisecond(s.getTime()), s.getValue());
                    macdHistTs.addOrUpdate(new Millisecond(h.getTime()), h.getValue());
                }
                TimeSeriesCollection macdCol = new TimeSeriesCollection();
                macdCol.addSeries(macdTs);
                macdCol.addSeries(macdSigTs);
                TimeSeriesCollection histCol = new TimeSeriesCollection();
                histCol.addSeries(macdHistTs);
                NumberAxis macdAxis = new NumberAxis("MACD");
                macdAxis.setAutoRangeIncludesZero(true);
                XYPlot macdPlot = new XYPlot(macdCol, null, macdAxis, new StandardXYItemRenderer());
                StandardXYItemRenderer macdRenderer = (StandardXYItemRenderer) macdPlot.getRenderer();
                macdRenderer.setSeriesPaint(0, Color.BLUE);
                macdRenderer.setSeriesPaint(1, Color.RED);
                macdRenderer.setSeriesStroke(0, new BasicStroke(2.0f));
                macdRenderer.setSeriesStroke(1, new BasicStroke(2.0f));
                macdPlot.setDataset(1, histCol);
                XYBarRenderer histRenderer = new XYBarRenderer() {
                    @Override
                    public Paint getItemPaint(int series, int item) {
                        double value = histCol.getYValue(series, item);
                        return value >= 0 ? Color.GREEN : Color.RED;
                    }
                };
                histRenderer.setBase(0.0);
                histRenderer.setDrawBarOutline(false);
                histRenderer.setShadowVisible(false);
                macdPlot.setRenderer(1, histRenderer);
                combined.add(macdPlot, 1);

                JFreeChart combinedChart = new JFreeChart(title, JFreeChart.DEFAULT_TITLE_FONT, combined, true);
                return combinedChart;
            } catch (Exception ex) {
                // fallback to main chart
                return candleChart;
            }
        }

        return candleChart;
    }

    private int findClosestIndex(List<KlineData> data, long time) {
        int closestIndex = -1;
        long minDiff = Long.MAX_VALUE;
        for (int i = 0; i < data.size(); i++) {
            KlineData d = data.get(i);
            long diff = Math.abs(d.getOpenTime().getTime() - time);
            if (diff < minDiff) {
                minDiff = diff;
                closestIndex = i;
            }
        }
        return closestIndex;
    }

    private String formatTooltip(KlineData data) {
        return String.format("<html>Time: %s<br/>Open: %.2f<br/>Close: %.2f<br/>High: %.2f<br/>Low: %.2f<br/>Volume: %.2f</html>",
                data.getOpenTime(),
                data.getOpen(),
                data.getClose(),
                data.getHigh(),
                data.getLow(),
                data.getVolume());
    }

    // Custom renderer that draws each candle horizontally spanning from openTime to closeTime
    // It's an inner (non-static) class so it can access the currentData list for closeTime values.
    private class CustomCandlestickRenderer extends CandlestickRenderer {
        @Override
        public Paint getItemPaint(int series, int item) {
            XYPlot plot = getPlot();
            if (plot != null && plot.getDataset() instanceof OHLCDataset) {
                OHLCDataset ohlc = (OHLCDataset) plot.getDataset();
                double open = ohlc.getOpenValue(series, item);
                double close = ohlc.getCloseValue(series, item);
                return open <= close ? getUpPaint() : getDownPaint();
            }
            return super.getItemPaint(series, item);
        }

        @Override
        public Paint getItemOutlinePaint(int series, int item) {
            return getItemPaint(series, item);
        }

        @Override
        public void drawItem(Graphics2D g2,
                             XYItemRendererState state,
                             Rectangle2D dataArea,
                             PlotRenderingInfo info,
                             XYPlot plot,
                             ValueAxis domainAxis,
                             ValueAxis rangeAxis,
                             org.jfree.data.xy.XYDataset dataset,
                             int series,
                             int item,
                             CrosshairState crosshairState,
                             int pass) {
            try {
                if (!(dataset instanceof OHLCDataset)) {
                    super.drawItem(g2, state, dataArea, info, plot, domainAxis, rangeAxis, dataset, series, item, crosshairState, pass);
                    return;
                }
                OHLCDataset ohlc = (OHLCDataset) dataset;

                // Get prices
                double open = ohlc.getOpenValue(series, item);
                double close = ohlc.getCloseValue(series, item);
                double high = ohlc.getHighValue(series, item);
                double low = ohlc.getLowValue(series, item);

                // Get time interval from currentData using the same index ordering used to create the dataset
                long tOpen = -1L;
                long tClose = -1L;
                if (item >= 0 && item < currentData.size()) {
                    KlineData kd = currentData.get(item);
                    if (kd != null) {
                        if (kd.getOpenTime() != null) tOpen = kd.getOpenTime().getTime();
                        if (kd.getCloseTime() != null) tClose = kd.getCloseTime().getTime();
                    }
                }

                // Fallback: if close time missing or invalid, use a small fixed width centered on open time
                Rectangle2D bar = dataArea;
                double xOpenJava2D;
                double xCloseJava2D;
                if (tOpen > 0 && tClose > 0 && tClose != tOpen) {
                    xOpenJava2D = domainAxis.valueToJava2D(tOpen, bar, plot.getDomainAxisEdge());
                    xCloseJava2D = domainAxis.valueToJava2D(tClose, bar, plot.getDomainAxisEdge());
                    // ensure left < right
                    if (xCloseJava2D < xOpenJava2D) {
                        double tmp = xOpenJava2D;
                        xOpenJava2D = xCloseJava2D;
                        xCloseJava2D = tmp;
                    }
                } else {
                    double x = domainAxis.valueToJava2D(ohlc.getXValue(series, item), bar, plot.getDomainAxisEdge());
                    xOpenJava2D = x - 3.0;
                    xCloseJava2D = x + 3.0;
                }

                double rawWidth = Math.max(1.0, xCloseJava2D - xOpenJava2D);
                // Shrink the candle slightly to leave a small gap between adjacent candles.
                // shrinkFactor: fraction of original width to keep (0.0-1.0). 0.92 keeps 92% width -> 8% gap.
                double shrinkFactor = 0.85;
                double keepWidth = rawWidth * shrinkFactor;
                double pad = (rawWidth - keepWidth) / 2.0;
                double xOpenAdj = xOpenJava2D + pad;
                double xCloseAdj = xCloseJava2D - pad;
                // Ensure we have at least 1 pixel width
                if (xCloseAdj - xOpenAdj < 1.0) {
                    double cx = (xOpenAdj + xCloseAdj) / 2.0;
                    xOpenAdj = cx - 0.5;
                    xCloseAdj = cx + 0.5;
                }
                double centerX = (xOpenAdj + xCloseAdj) / 2.0;

                // Convert prices to Java2D y coordinates
                double yOpen = rangeAxis.valueToJava2D(open, bar, plot.getRangeAxisEdge());
                double yClose = rangeAxis.valueToJava2D(close, bar, plot.getRangeAxisEdge());
                double yHigh = rangeAxis.valueToJava2D(high, bar, plot.getRangeAxisEdge());
                double yLow = rangeAxis.valueToJava2D(low, bar, plot.getRangeAxisEdge());

                // Determine colors
                Paint itemPaint = getItemPaint(series, item);
                Paint outlinePaint = getItemOutlinePaint(series, item);

                // Draw wick: vertical line at center (thicker stroke for better visibility)
                Stroke oldStroke = g2.getStroke();
                // Use a slightly thicker, rounded-cap stroke for the wick
                g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
                g2.setPaint(itemPaint);
                int xC = (int) Math.round(centerX);
                int yH = (int) Math.round(yHigh);
                int yL = (int) Math.round(yLow);
                g2.drawLine(xC, yH, xC, yL);

                // Draw body as rectangle spanning xOpenJava2D..xCloseJava2D and between open/close prices
                double bodyTop = Math.min(yOpen, yClose);
                double bodyBottom = Math.max(yOpen, yClose);
                int bodyX = (int) Math.round(xOpenAdj);
                int bodyY = (int) Math.round(bodyTop);
                int bodyW = Math.max(1, (int) Math.round(xCloseAdj - xOpenAdj));
                int bodyH = Math.max(1, (int) Math.round(bodyBottom - bodyTop));

                // fill
                g2.setPaint(itemPaint);
                g2.fillRect(bodyX, bodyY, bodyW, bodyH);

                // outline
                g2.setPaint(outlinePaint);
                g2.drawRect(bodyX, bodyY, bodyW, bodyH);

                g2.setStroke(oldStroke);
            } catch (Throwable ex) {
                // fallback to default drawing to avoid breaking UI
                try {
                    super.drawItem(g2, state, dataArea, info, plot, domainAxis, rangeAxis, dataset, series, item, crosshairState, pass);
                } catch (Exception ignore) {}
            }
        }
    }

    private Range getCurrentDomainRange() {
        try {
            JFreeChart chart = chartPanel.getChart();
            if (chart == null) return null;
            Plot plot = chart.getPlot();
            XYPlot xyPlot;
            if (plot instanceof CombinedDomainXYPlot) {
                CombinedDomainXYPlot combined = (CombinedDomainXYPlot) plot;
                xyPlot = (XYPlot) combined.getSubplots().get(0);
            } else {
                xyPlot = (XYPlot) plot;
            }
            ValueAxis domainAxis = xyPlot.getDomainAxis();
            return domainAxis.getRange();
        } catch (Exception e) {
            return null;
        }
    }

    private void setDomainRange(Range range) {
        try {
            JFreeChart chart = chartPanel.getChart();
            if (chart == null) return;
            Plot plot = chart.getPlot();
            XYPlot xyPlot;
            if (plot instanceof CombinedDomainXYPlot) {
                CombinedDomainXYPlot combined = (CombinedDomainXYPlot) plot;
                xyPlot = (XYPlot) combined.getSubplots().get(0);
            } else {
                xyPlot = (XYPlot) plot;
            }
            ValueAxis domainAxis = xyPlot.getDomainAxis();

            // Get data time range
            Range dataRange = getDataTimeRange();
            if (dataRange != null) {
                double dataLower = dataRange.getLowerBound();
                double dataUpper = dataRange.getUpperBound();
                double rangeLower = range.getLowerBound();
                double rangeUpper = range.getUpperBound();

                // Check if range overlaps with data range
                boolean overlaps = rangeLower < dataUpper && rangeUpper > dataLower;
                if (!overlaps) {
                    // If no overlap, set to full data range
                    domainAxis.setRange(dataLower, dataUpper + (dataUpper - dataLower) * 0.05); // add 5% padding
                } else {
                    // Set the provided range
                    domainAxis.setRange(range);
                }
            } else {
                domainAxis.setRange(range);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private Range getDataTimeRange() {
        if (currentData == null || currentData.isEmpty()) {
            return null;
        }
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        for (KlineData data : currentData) {
            long time = data.getOpenTime().getTime();
            if (time < minTime) minTime = time;
            if (time > maxTime) maxTime = time;
        }
        return new Range(minTime, maxTime);
    }

    /**
     * Recompute the range axis (Y-axis) to fit the candles currently visible in the domain axis range.
     * This makes Y axis auto-scale according to the visible data instead of the entire dataset.
     */
    private void updateRangeAxisForVisibleData() {
        try {
            JFreeChart chart = chartPanel.getChart();
            if (chart == null) return;
            Plot plot = chart.getPlot();
            XYPlot mainPlot;
            if (plot instanceof CombinedDomainXYPlot) {
                CombinedDomainXYPlot combined = (CombinedDomainXYPlot) plot;
                mainPlot = (XYPlot) combined.getSubplots().get(0);
            } else {
                mainPlot = (XYPlot) plot;
            }

            ValueAxis domainAxis = mainPlot.getDomainAxis();
            ValueAxis rangeAxisGeneric = mainPlot.getRangeAxis();
            if (!(rangeAxisGeneric instanceof NumberAxis)) return;
            NumberAxis rangeAxis = (NumberAxis) rangeAxisGeneric;

            Range domainRange = domainAxis.getRange();
            if (domainRange == null) return;
            double domainLower = domainRange.getLowerBound();
            double domainUpper = domainRange.getUpperBound();

            if (currentData == null || currentData.isEmpty()) return;

            double minPrice = Double.MAX_VALUE;
            double maxPrice = Double.MIN_VALUE;
            boolean any = false;
            for (KlineData d : currentData) {
                if (d == null) continue;
                long t = d.getOpenTime().getTime();
                // include candle if its open time falls within visible domain range
                if (t >= domainLower && t <= domainUpper) {
                    any = true;
                    double low = d.getLow().doubleValue();
                    double high = d.getHigh().doubleValue();
                    if (low < minPrice) minPrice = low;
                    if (high > maxPrice) maxPrice = high;
                }
            }
            if (!any) {
                // if no candles exactly inside range (edge cases), fall back to nearest ones
                for (KlineData d : currentData) {
                    if (d == null) continue;
                    double low = d.getLow().doubleValue();
                    double high = d.getHigh().doubleValue();
                    if (low < minPrice) minPrice = low;
                    if (high > maxPrice) maxPrice = high;
                }
            }

            if (minPrice == Double.MAX_VALUE || maxPrice == Double.MIN_VALUE) return;

            // Add small padding (1% of span or a tiny absolute amount)
            double span = Math.max(1e-8, maxPrice - minPrice);
            double padding = span * 0.02; // 2% padding
            if (padding < 1e-6) padding = Math.max(1e-6, span * 0.01);

            final double newLower = Math.max(0, minPrice - padding);
            final double newUpper = maxPrice + padding;

            // Apply on EDT
            if (SwingUtilities.isEventDispatchThread()) {
                rangeAxis.setAutoRange(false);
                rangeAxis.setRange(newLower, newUpper);
            } else {
                SwingUtilities.invokeLater(() -> {
                    rangeAxis.setAutoRange(false);
                    rangeAxis.setRange(newLower, newUpper);
                });
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // register AWT listener when component is shown/added
        if (chartAwtListener == null) {
            chartAwtListener = evt -> {
                if (!(evt instanceof MouseEvent)) return;
                // Handle mouse wheel separately so we can use MouseWheelEvent API
                if (evt instanceof MouseWheelEvent) {
                    MouseWheelEvent mwe = (MouseWheelEvent) evt;
                    Component src = mwe.getComponent();
                    if (src == null) return;
                    if (!SwingUtilities.isDescendingFrom(src, chartPanel)) return;
                    try {
                        // convert point to chartPanel coords
                        Point p = SwingUtilities.convertPoint(src, mwe.getPoint(), chartPanel);

                        JFreeChart chart = chartPanel.getChart();
                        if (chart == null) return;
                        Plot plot = chart.getPlot();
                        XYPlot xyPlot;
                        if (plot instanceof CombinedDomainXYPlot) {
                            xyPlot = (XYPlot) ((CombinedDomainXYPlot) plot).getSubplots().get(0);
                        } else {
                            xyPlot = (XYPlot) plot;
                        }
                        ValueAxis domainAxis = xyPlot.getDomainAxis();
                        Rectangle2D dataArea = chartPanel.getScreenDataArea();
                        if (dataArea == null) return;

                        // Domain value under mouse cursor
                        double mouseDomain = domainAxis.java2DToValue(p.getX(), dataArea, xyPlot.getDomainAxisEdge());

                        // Current range
                        Range range = domainAxis.getRange();
                        double lower = range.getLowerBound();
                        double upper = range.getUpperBound();

                        // Zoom factor: wheel up (negative rotation) -> zoom in
                        double factor = mwe.getWheelRotation() < 0 ? 0.9 : 1.1;

                        // Compute new bounds keeping mouseDomain fixed in data coords
                        double newLower = mouseDomain - (mouseDomain - lower) * factor;
                        double newUpper = mouseDomain + (upper - mouseDomain) * factor;

                        // Minimum length clamp
                        double minLength = 1000 * 60; // 1 minute in ms
                        if (newUpper - newLower < minLength) {
                            return;
                        }

                        // Clamp to data range
                        Range dataRange = getDataTimeRange();
                        if (dataRange != null) {
                            double dataLower = dataRange.getLowerBound();
                            double dataUpper = dataRange.getUpperBound();
                            // Ensure we don't go beyond data range (allow small padding)
                            double padding = (newUpper - newLower) * 0.05;
                            if (newLower < dataLower) {
                                newLower = dataLower;
                                newUpper = newLower + (newUpper - newLower);
                            }
                            if (newUpper > dataUpper + padding) {
                                newUpper = dataUpper + padding;
                                newLower = newUpper - (newUpper - newLower);
                            }
                        }

                        domainAxis.setRange(newLower, newUpper);
                        chartPanel.repaint();
                        // consume wheel event so ChartPanel won't do its default
                        mwe.consume();
                    } catch (Throwable ignore) {
                    }
                    return;
                }

                MouseEvent me = (MouseEvent) evt;
                Component src = me.getComponent();
                if (src == null) return;
                if (!SwingUtilities.isDescendingFrom(src, chartPanel)) return;

                int id = me.getID();
                try {
                    if (id == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(me)) {
                        // convert point to chartPanel coords
                        Point p = SwingUtilities.convertPoint(src, me.getPoint(), chartPanel);
                        userPanning = true;
                        lastMouseX = p.x;
                        cancelAnimation();
                        me.consume();
                        clearChartPanelZoomRectangle(chartPanel);
                    } else if (id == MouseEvent.MOUSE_DRAGGED && SwingUtilities.isLeftMouseButton(me)) {
                        if (userPanning) {
                            Point p = SwingUtilities.convertPoint(src, me.getPoint(), chartPanel);
                            int dx = p.x - lastMouseX;
                            // invert pan direction
                            panDomainByPixels(dx);
                            lastMouseX = p.x;
                            clearChartPanelZoomRectangle(chartPanel);
                            me.consume();
                        }
                    } else if (id == MouseEvent.MOUSE_RELEASED && SwingUtilities.isLeftMouseButton(me)) {
                        if (userPanning) {
                            userPanning = false;
                            lastMouseX = -1;
                            userMovedFromRightEdge = !isAtRightEdge();
                            clearChartPanelZoomRectangle(chartPanel);
                            me.consume();
                        }
                    }
                } catch (Throwable ignore) {
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(chartAwtListener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        }
    }

    @Override
    public void removeNotify() {
        // unregister AWT listener to avoid memory leaks
        try {
            if (chartAwtListener != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(chartAwtListener);
                chartAwtListener = null;
            }
        } catch (Throwable ignore) {
        }
        // detach domain axis listener to avoid leaks
        try {
            if (lastDomainAxis != null && domainAxisListener != null) {
                try { lastDomainAxis.removeChangeListener(domainAxisListener); } catch (Throwable ignore) {}
                lastDomainAxis = null;
            }
        } catch (Throwable ignore) {}
        super.removeNotify();
    }

    private void positionLoadingLabel() {
        try {
            if (loadingLabel == null || chartPanel == null) return;
            int w = loadingLabel.getPreferredSize().width;
            int h = loadingLabel.getPreferredSize().height;
            // place near top-left inside chart area with small margin
            Rectangle bounds = chartPanel.getBounds();
            int x = 12;
            int y = 12;
            // ensure within chart panel
            if (x + w > bounds.width - 8) x = Math.max(8, bounds.width - w - 8);
            if (y + h > bounds.height - 8) y = Math.max(8, bounds.height - h - 8);
            loadingLabel.setBounds(x, y, w, h);
        } catch (Throwable ignore) {
        }
    }

    private void setLoadingIndicator(boolean visible) {
        // Ensure EDT
        if (SwingUtilities.isEventDispatchThread()) {
            loadingLabel.setVisible(visible);
            loadingLabel.repaint();
        } else {
            SwingUtilities.invokeLater(() -> {
                loadingLabel.setVisible(visible);
                loadingLabel.repaint();
            });
        }
    }
}

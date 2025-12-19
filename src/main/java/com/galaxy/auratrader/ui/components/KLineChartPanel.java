package com.galaxy.auratrader.ui.components;

import com.galaxy.auratrader.model.KlineData;
import com.galaxy.auratrader.service.IndicatorService;
import com.galaxy.auratrader.service.IndicatorService.IndicatorPoint;
import com.galaxy.auratrader.service.IndicatorService.IndicatorResult;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartMouseEvent;
import org.jfree.chart.ChartMouseListener;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.AxisLocation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.chart.renderer.xy.StandardXYItemRenderer;
import org.jfree.chart.renderer.xy.XYBarRenderer;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.jfree.data.xy.DefaultHighLowDataset;
import org.jfree.data.xy.OHLCDataset;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class KLineChartPanel extends JPanel {

    private final ChartPanel chartPanel;
    private List<KlineData> currentData = new ArrayList<>();
    private IndicatorResult indicators = null;

    public KLineChartPanel() {
        setLayout(new BorderLayout());
        // Initial empty chart
        JFreeChart chart = createChart(null, "No Data");
        // Use an anonymous ChartPanel subclass to draw a sliding latest-price label on top of the chart
        chartPanel = new ChartPanel(chart) {
            @Override
            public void paintComponent(Graphics g) {
                super.paintComponent(g);

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

                    // Convert latest price value to Java2D y coordinate
                    double yJava2D = rangeAxis.valueToJava2D(latestPrice, dataArea, mainPlot.getRangeAxisEdge());

                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        // Prepare label text
                        String text = String.format("%.2f", latestPrice);

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

                        // Clamp y inside the data area
                        int minY = (int) Math.ceil(dataArea.getMinY());
                        int maxY = (int) Math.floor(dataArea.getMaxY());
                        int y = (int) Math.round(yJava2D) - rectHeight / 2;
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

    public void setIndicators(IndicatorResult indicators) {
        this.indicators = indicators;
        if (!currentData.isEmpty()) {
            DefaultHighLowDataset dataset = createDataset(currentData, chartPanel.getChart().getTitle().getText());
            JFreeChart chart = createChart(dataset, chartPanel.getChart().getTitle().getText());
            chartPanel.setChart(chart);
        }
    }

    public void updateData(List<KlineData> klineDataList, String title) {
        this.currentData = new ArrayList<>(klineDataList);
        DefaultHighLowDataset dataset = createDataset(currentData, title);
        JFreeChart chart = createChart(dataset, title);
        chartPanel.setChart(chart);
    }

    public void updateLatestKline(KlineData latestKline) {
        if (currentData.isEmpty()) {
            currentData.add(latestKline);
        } else {
            KlineData last = currentData.get(currentData.size() - 1);
            if (last.getOpenTime().equals(latestKline.getOpenTime())) {
                // Update the last one
                currentData.set(currentData.size() - 1, latestKline);
            } else {
                // Append new
                currentData.add(latestKline);
            }
        }
        // Update chart
        DefaultHighLowDataset dataset = createDataset(currentData, chartPanel.getChart().getTitle().getText());
        JFreeChart chart = createChart(dataset, chartPanel.getChart().getTitle().getText());
        chartPanel.setChart(chart);
        // Repaint so the moving price label updates
        chartPanel.repaint();
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

        CandlestickRenderer renderer = new ColoredCandlestickRenderer();
        renderer.setDrawVolume(true);
        renderer.setUpPaint(Color.GREEN);
        renderer.setDownPaint(Color.RED);
        renderer.setUseOutlinePaint(true); // enable outline paint usage
        mainPlot.setRenderer(renderer);

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

    // Custom renderer to ensure outlines and wicks use the same color as the candle body
    private static class ColoredCandlestickRenderer extends CandlestickRenderer {
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
            XYPlot plot = getPlot();
            if (plot != null && plot.getDataset() instanceof OHLCDataset) {
                OHLCDataset ohlc = (OHLCDataset) plot.getDataset();
                double open = ohlc.getOpenValue(series, item);
                double close = ohlc.getCloseValue(series, item);
                return open <= close ? getUpPaint() : getDownPaint();
            }
            return super.getItemOutlinePaint(series, item);
        }
    }
}

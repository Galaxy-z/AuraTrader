package com.galaxy.auratrader.ui;

import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.AllOrdersResponseInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.FuturesAccountBalanceV2ResponseInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.UserCommissionRateResponse;
import com.formdev.flatlaf.FlatLightLaf;
import com.galaxy.auratrader.model.DataPool;
import com.galaxy.auratrader.model.DataPoolObserver;
import com.galaxy.auratrader.model.KlineData;
import com.galaxy.auratrader.model.Notification;
import com.galaxy.auratrader.service.BinanceService;
import com.galaxy.auratrader.service.IndicatorService;
import com.galaxy.auratrader.service.IndicatorService.IndicatorResult;
import com.galaxy.auratrader.ui.components.KLineChartPanel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.text.DecimalFormat;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@Profile("!test")
@Slf4j
public class MainFrame extends JFrame implements DataPoolObserver {

    private final BinanceService binanceService;
    private final IndicatorService indicatorService;
    private final AIChatFrame aiChatFrame;
    private KLineChartPanel chartPanel;
    private JComboBox<String> pairComboBox;
    private JComboBox<String> intervalComboBox;
    private JPanel balancePanel;
    private JPanel balanceListPanel;
    // Positions UI
    private JPanel positionsPanel;
    private JTable positionsTable;
    private DefaultTableModel positionsTableModel;
    // Card panel that holds either the list view or pie chart view for balances
    private JPanel balanceContentPanel;
    private PieChartPanel pieChartPanel;
    private JToggleButton balanceViewToggle;
    private JTabbedPane tabbedPane;

    // Orders UI
    private JPanel ordersPanel;
    private JTable ordersTable;
    private DefaultTableModel ordersTableModel;

    // Notifications UI
    private JPanel notificationsPanel;
    private DefaultListModel<Notification> notificationsListModel;
    private JList<Notification> notificationsList;

    // Status Bar
    private JLabel statusBar;


    public MainFrame(BinanceService binanceService, IndicatorService indicatorService, AIChatFrame aiChatFrame) {
        this.binanceService = binanceService;
        this.indicatorService = indicatorService;
        this.aiChatFrame = aiChatFrame;
        DataPool.getInstance().addObserver(this); // 注册为数据池观察者
        initUI();
    }

    private void initUI() {
        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (Exception ex) {
            System.err.println("Failed to initialize LaF");
        }

        // 设置窗口图标
        try {
            Image icon = Toolkit.getDefaultToolkit().getImage(getClass().getResource("/icon.png"));
            setIconImage(icon);
        } catch (Exception e) {
            // 可选：打印异常或忽略
        }

        setTitle("AuraTrader - Contract Kline Chart");
        setSize(1200, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Top area: toolbar + control panel
        JToolBar toolBar = new JToolBar();
        JButton aiButton = new JButton("AI");
        aiButton.addActionListener(e -> {
            if (aiChatFrame != null) {
                aiChatFrame.setVisible(true);
                aiChatFrame.toFront();
            } else {
                JOptionPane.showMessageDialog(MainFrame.this, "AI chat is not available", "Info", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        toolBar.add(aiButton);

        // A container to hold toolbar and control panel vertically
        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BorderLayout());
        topContainer.add(toolBar, BorderLayout.NORTH);

        // Top Panel for controls
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        // Pair Selector
        List<String> pairs = binanceService.getPairs();
        pairComboBox = new JComboBox<>(pairs.toArray(new String[0]));
        pairComboBox.addActionListener(e -> {
            String pair = (String) pairComboBox.getSelectedItem();
            // update DataPool with current pair selection
            DataPool.getInstance().setCurrentPair(pair);
            // 新增：获取手续费率
            binanceService.getCommissionRate(pair);
            loadData();
        });
        controlPanel.add(new JLabel("Pair:"));
        controlPanel.add(pairComboBox);

        // Interval Selector
        String[] intervals = {"1m", "5m", "15m", "1h", "4h", "1d"};
        intervalComboBox = new JComboBox<>(intervals);
        intervalComboBox.addActionListener(e -> {
            String interval = (String) intervalComboBox.getSelectedItem();
            // update DataPool with current interval selection
            DataPool.getInstance().setCurrentInterval(interval);
            loadData();
        });
        controlPanel.add(new JLabel("Interval:"));
        controlPanel.add(intervalComboBox);

        // Load Button
        JButton loadButton = new JButton("刷新");
        loadButton.addActionListener(e -> {
            loadData();
            refreshAll();
        });
        controlPanel.add(loadButton);

        topContainer.add(controlPanel, BorderLayout.CENTER);
        add(topContainer, BorderLayout.NORTH);

        // Chart Panel
        chartPanel = new KLineChartPanel();
        // Create tabbed pane and add chart and balance tabs
        tabbedPane = new JTabbedPane();

        // Balance Panel
        // Use a BorderLayout so we can have a control row (refresh button) and
        // a scrollable list area for balances.
        balancePanel = new JPanel(new BorderLayout());
        balancePanel.setBorder(BorderFactory.createTitledBorder("Account Balance"));
        balancePanel.setPreferredSize(new Dimension(200, 0));

        // inner panel holds balance lines vertically and will be placed inside a scroll pane
        balanceListPanel = new JPanel();
        balanceListPanel.setLayout(new BoxLayout(balanceListPanel, BoxLayout.Y_AXIS));
        JScrollPane balanceScrollPane = new JScrollPane(balanceListPanel);

        // Pie chart panel (initially empty)
        pieChartPanel = new PieChartPanel();
        JScrollPane pieScrollPane = new JScrollPane(pieChartPanel);

        // CardLayout to swap between list and pie chart
        balanceContentPanel = new JPanel(new CardLayout());
        balanceContentPanel.add(balanceScrollPane, "LIST");
        balanceContentPanel.add(pieScrollPane, "PIE");
        balancePanel.add(balanceContentPanel, BorderLayout.CENTER);

        // control panel with a refresh button for balances
        JPanel balanceCtl = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton balanceRefresh = new JButton("刷新余额");
        balanceRefresh.addActionListener(e -> loadBalance());
        // Toggle between list and pie chart
        balanceViewToggle = new JToggleButton("饼图");
        balanceViewToggle.addActionListener(e -> {
            CardLayout cl = (CardLayout) (balanceContentPanel.getLayout());
            if (balanceViewToggle.isSelected()) {
                balanceViewToggle.setText("列表");
                cl.show(balanceContentPanel, "PIE");
            } else {
                balanceViewToggle.setText("饼图");
                cl.show(balanceContentPanel, "LIST");
            }
            // repaint to ensure pie chart draws immediately when switching
            pieChartPanel.revalidate();
            pieChartPanel.repaint();
        });
        balanceCtl.add(balanceRefresh);
        balanceCtl.add(balanceViewToggle);
        balancePanel.add(balanceCtl, BorderLayout.NORTH);

        // Orders Panel
        ordersPanel = new JPanel(new BorderLayout());
        ordersPanel.setBorder(BorderFactory.createTitledBorder("All Orders"));
        String[] cols = {
                "订单时间",
                "订单ID",
                "交易对",
                "买卖方向",
                "持仓方向",
                "订单状态",
                "成交量",
                "原始委托数量",
                "委托价格",
        };
        ordersTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        ordersTable = new JTable(ordersTableModel);
        ordersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ordersTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = ordersTable.getSelectedRow();
                    if (viewRow >= 0) {
                        int modelRow = ordersTable.convertRowIndexToModel(viewRow);
                        Object idObj = ordersTableModel.getValueAt(modelRow, 0);
                        Long orderId = null;
                        if (idObj instanceof Number) {
                            orderId = ((Number) idObj).longValue();
                        } else {
                            try {
                                orderId = Long.parseLong(String.valueOf(idObj));
                            } catch (Exception ex) {
                                // ignore parse error
                            }
                        }

                        AllOrdersResponseInner target = null;
                        List<AllOrdersResponseInner> orders = DataPool.getInstance().getOrders();
                        if (orders != null && orderId != null) {
                            for (AllOrdersResponseInner o : orders) {
                                if (o != null && orderId.equals(o.getOrderId())) {
                                    target = o;
                                    break;
                                }
                            }
                        }
                        // fallback: if not found by id, try by row index
                        if (target == null && orders != null && modelRow >= 0 && modelRow < orders.size()) {
                            target = orders.get(modelRow);
                        }

                        if (target != null) {
                            showOrderDetails(target);
                        } else {
                            JOptionPane.showMessageDialog(MainFrame.this, "Order details not found", "Info", JOptionPane.INFORMATION_MESSAGE);
                        }
                    }
                }
            }
        });
        JScrollPane ordersScroll = new JScrollPane(ordersTable);
        ordersPanel.add(ordersScroll, BorderLayout.CENTER);

        // Orders control: refresh button
        JButton ordersRefresh = new JButton("刷新订单");
        ordersRefresh.addActionListener(e -> refreshOrders());
        JPanel ordersCtl = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ordersCtl.add(ordersRefresh);
        ordersPanel.add(ordersCtl, BorderLayout.NORTH);

        // Notifications Panel
        notificationsPanel = new JPanel(new BorderLayout());
        notificationsPanel.setBorder(BorderFactory.createTitledBorder("Notifications"));
        notificationsListModel = new DefaultListModel<>();
        notificationsList = new JList<>(notificationsListModel);
        notificationsList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel lbl = new JLabel();
            if (value != null) {
                lbl.setText(String.format("%1$tF %1$tT - %2$s: %3$s", value.getTime(), value.getTitle(), value.getMessage()));
            }
            return lbl;
        });
        JScrollPane notifScroll = new JScrollPane(notificationsList);
        notificationsPanel.add(notifScroll, BorderLayout.CENTER);

        tabbedPane.addTab("Chart", chartPanel);
        tabbedPane.addTab("Account Balance", balancePanel);
        // Positions panel - risk view
        positionsPanel = new JPanel(new BorderLayout());
        positionsPanel.setBorder(BorderFactory.createTitledBorder("Positions Risk"));
        // Updated columns to show key risk metrics requested by the user
        String[] posCols = new String[]{
                "交易对",
                "持仓方向",
                "持仓数量",
                "开仓平均价",
                "盈亏平衡价",
                "标记价格",
                "未实现盈亏",
                "强平价格",
                "名义价值",
                "初始保证金",
                "维持保证金",
                "保证金资产",
                "更新时间"
        };
        positionsTableModel = new DefaultTableModel(posCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        positionsTable = new JTable(positionsTableModel);
        JScrollPane posScroll = new JScrollPane(positionsTable);
        positionsPanel.add(posScroll, BorderLayout.CENTER);
        JPanel posCtl = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton posRefresh = new JButton("刷新持仓");
        posRefresh.addActionListener(e -> refreshPositions());
        posCtl.add(posRefresh);
        positionsPanel.add(posCtl, BorderLayout.NORTH);
        tabbedPane.addTab("Positions Risk", positionsPanel);
        tabbedPane.addTab("Orders", ordersPanel);
        tabbedPane.addTab("Notifications", notificationsPanel);

        add(tabbedPane, BorderLayout.CENTER);

        // Status Bar
        statusBar = new JLabel("Status: Ready");
        statusBar.setBorder(BorderFactory.createLoweredBevelBorder());
        add(statusBar, BorderLayout.SOUTH);

        // Note: balancePanel is now part of the tabbed pane

        // Load balance initially
        loadBalance();

        // 自动在主界面第一次显示时触发一次刷新（确保已有默认选择），并启动 account stream
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                if (pairComboBox != null && pairComboBox.getItemCount() > 0 && pairComboBox.getSelectedIndex() < 0) {
                    pairComboBox.setSelectedIndex(0);
                }
                if (intervalComboBox != null && intervalComboBox.getItemCount() > 0 && intervalComboBox.getSelectedIndex() < 0) {
                    intervalComboBox.setSelectedIndex(0);
                }
                // 异步加载数据（loadData 本身会在后台线程处理）
                loadData();
                refreshAll();
                // 新增：界面打开时刷新手续费
                String pair = (String) pairComboBox.getSelectedItem();
                if (pair != null && !pair.isEmpty()) {
                    binanceService.getCommissionRate(pair);
                }
                // Start account update stream
                binanceService.startAccountUpdateStream();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                // Stop background streams
                binanceService.stopAccountUpdateStream();
                binanceService.stopStreaming();
            }
        });
    }

    private void loadData() {
        String pair = (String) pairComboBox.getSelectedItem();
        String interval = (String) intervalComboBox.getSelectedItem();

        if (pair == null || interval == null) {
            return;
        }

        // 新增：每次刷新时都获取手续费
        binanceService.getCommissionRate(pair);

        // Stop previous stream via service
        binanceService.stopStreaming();

        SwingWorker<List<KlineData>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<KlineData> doInBackground() throws Exception {
                return binanceService.getKlineData(pair, interval);
            }

            @Override
            protected void done() {
                try {
                    // 只需触发数据池更新，UI刷新由onDataUpdated统一处理
                    // List<KlineData> data = get();
                    // chartPanel.updateData(data, pair + " - " + interval);
                    // IndicatorResult ind = indicatorService.computeIndicators(data, 20, 14);
                    // chartPanel.setIndicators(ind);

                    // Start real-time streaming via service
                    binanceService.startStreaming(pair, interval);

                    // Fetch leverage for the pair
                    binanceService.getSymbolConfiguration(pair);
                } catch (Exception e) {
                    log.error("Error loading data", e);
                    JOptionPane.showMessageDialog(MainFrame.this, "Error loading data: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void loadBalance() {
        SwingWorker<List<FuturesAccountBalanceV2ResponseInner>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<FuturesAccountBalanceV2ResponseInner> doInBackground() throws Exception {
                return binanceService.getAccountBalance();
            }

            @Override
            protected void done() {
                try {
                    // Set balances into DataPool so observers (including this frame) get a single canonical update
                    List<FuturesAccountBalanceV2ResponseInner> balances = get();
                    DataPool.getInstance().setBalances(balances);
                } catch (Exception e) {
                    log.error("Error loading balance", e);
                    JOptionPane.showMessageDialog(MainFrame.this, "Error loading balance: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    // Refresh orders for current pair
    private void refreshOrders() {
        String pair = (String) pairComboBox.getSelectedItem();
        if (pair == null || pair.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a pair first", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SwingWorker<List<AllOrdersResponseInner>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<AllOrdersResponseInner> doInBackground() throws Exception {
                return binanceService.getAllOrders(pair);
            }

            @Override
            protected void done() {
                try {
                    // UI will be updated via DataPool observer when service sets orders in pool
                    // But we can also force immediate table update here by reading DataPool
                    List<AllOrdersResponseInner> orders = get();
                    DataPool.getInstance().setOrders(orders);
                } catch (Exception e) {
                    log.error("Error loading orders", e);
                    JOptionPane.showMessageDialog(MainFrame.this, "Error loading orders: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    // Refresh positions for current pair
    private void refreshPositions() {
        String pair = (String) pairComboBox.getSelectedItem();
        if (pair == null || pair.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a pair first", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SwingWorker<List<com.binance.connector.client.derivatives_trading_usds_futures.rest.model.PositionInformationV3ResponseInner>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<com.binance.connector.client.derivatives_trading_usds_futures.rest.model.PositionInformationV3ResponseInner> doInBackground() throws Exception {
                return binanceService.getPositions(pair);
            }

            @Override
            protected void done() {
                try {
                    List<com.binance.connector.client.derivatives_trading_usds_futures.rest.model.PositionInformationV3ResponseInner> positions = get();
                    DataPool.getInstance().setPositions(positions);
                } catch (Exception e) {
                    log.error("Error loading positions", e);
                    JOptionPane.showMessageDialog(MainFrame.this, "Error loading positions: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void refreshAll() {
        loadBalance();
        refreshPositions();
        refreshOrders();
    }

    @Override
    public void onDataUpdated(DataPool.DataType type) {
        // 确保在EDT线程刷新
        SwingUtilities.invokeLater(() -> {
            if (type == DataPool.DataType.KLINE) {
                List<KlineData> data = DataPool.getInstance().getKlineData();
                String pair = (String) pairComboBox.getSelectedItem();
                String interval = (String) intervalComboBox.getSelectedItem();
                chartPanel.updateData(data, (pair != null ? pair : "") + " - " + (interval != null ? interval : ""));
                // Prefer indicators from DataPool if available (keeps UI logic out of data source)
                IndicatorResult ind = DataPool.getInstance().getIndicators();
                if (ind == null || ind.getMa() == null || ind.getMa().isEmpty()) {
                    // fallback: compute if pool not populated
                    ind = indicatorService.computeIndicators(data, 20, 14);
                }
                chartPanel.setIndicators(ind);
            } else if (type == DataPool.DataType.BALANCE) {
                List<FuturesAccountBalanceV2ResponseInner> balances = DataPool.getInstance().getBalances();
                // update list view
                balanceListPanel.removeAll();
                if (balances != null) {
                    for (FuturesAccountBalanceV2ResponseInner balance : balances) {
                        String balStr = balance.getBalance();
                        double bal = 0.0;
                        if (balStr != null) {
                            try {
                                bal = Double.parseDouble(balStr);
                            } catch (NumberFormatException ex) {
                                log.warn("Failed to parse balance for {}: {}", balance.getAsset(), balStr, ex);
                            }
                        }
                        String text = String.format("%s: %.2f", balance.getAsset(), bal);
                        balanceListPanel.add(new JLabel(text));
                    }
                }
                balanceListPanel.revalidate();
                balanceListPanel.repaint();

                // update pie chart data
                pieChartPanel.setBalances(balances);
                pieChartPanel.revalidate();
                pieChartPanel.repaint();
             } else if (type == DataPool.DataType.INDICATOR) {
                // Update chart with new indicators
                IndicatorResult ind = DataPool.getInstance().getIndicators();
                chartPanel.setIndicators(ind);
            } else if (type == DataPool.DataType.PAIR || type == DataPool.DataType.INTERVAL) {
                // keep UI combo boxes synchronized with DataPool if needed
                String pair = DataPool.getInstance().getCurrentPair();
                String interval = DataPool.getInstance().getCurrentInterval();
                if (pair != null && !pair.equals((String) pairComboBox.getSelectedItem())) {
                    pairComboBox.setSelectedItem(pair);
                }
                if (interval != null && !interval.equals((String) intervalComboBox.getSelectedItem())) {
                    intervalComboBox.setSelectedItem(interval);
                }
            } else if (type == DataPool.DataType.ORDERS) {
                // Update orders table
                List<AllOrdersResponseInner> orders = DataPool.getInstance().getOrders();
                ordersTableModel.setRowCount(0);
                if (orders != null) {
                    for (AllOrdersResponseInner o : orders) {
                        Object[] row = new Object[] {
                                new Date(o.getTime()),
                                o.getOrderId(),
                                o.getSymbol(),
                                o.getSide(),
                                o.getPositionSide(),
                                o.getStatus(),
                                o.getExecutedQty(),
                                o.getOrigQty(),
                                o.getPrice(),
                        };
                        ordersTableModel.addRow(row);
                    }
                }
                ordersTableModel.fireTableDataChanged();
            } else if (type == DataPool.DataType.NOTIFICATIONS) {
                // Update notifications list UI and show transient popup for the newest notification
                List<Notification> notifications = DataPool.getInstance().getNotifications();
                notificationsListModel.clear();
                if (notifications != null) {
                    for (Notification n : notifications) {
                        notificationsListModel.addElement(n);
                    }
                }
                // show transient popup for newest
                if (!notificationsListModel.isEmpty()) {
                    Notification top = notificationsListModel.get(0);
                    showTransientNotification(top.getTitle(), top.getMessage());
                }
            } else if (type == DataPool.DataType.POSITIONS) {
                // Update positions table using a safe Map conversion so we don't rely on SDK getters
                List<?> positions = DataPool.getInstance().getPositions();
                positionsTableModel.setRowCount(0);
                if (positions != null) {
                    ObjectMapper mapper = new ObjectMapper();
                    for (Object pObj : positions) {
                        try {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> m = mapper.convertValue(pObj, java.util.Map.class);
                            Object symbol = m.getOrDefault("symbol", m.getOrDefault("Symbol", ""));
                            Object positionSide = m.getOrDefault("positionSide", m.getOrDefault("positionSide", m.getOrDefault("PositionSide", "BOTH")));
                            Object positionAmt = m.getOrDefault("positionAmt", m.getOrDefault("positionAmt", "0"));
                            Object entryPrice = m.getOrDefault("entryPrice", "");
                            Object breakEvenPrice = m.getOrDefault("breakEvenPrice", m.getOrDefault("breakEvenPrice", ""));
                            Object markPrice = m.getOrDefault("markPrice", "");
                            Object unrealized = m.getOrDefault("unRealizedProfit", m.getOrDefault("unrealizedProfit", m.getOrDefault("unRealizedProfit", "")));
                            Object liquidationPrice = m.getOrDefault("liquidationPrice", "");
                            Object notional = m.getOrDefault("notional", "");
                            Object initialMargin = m.getOrDefault("initialMargin", m.getOrDefault("positionInitialMargin", ""));
                            Object maintMargin = m.getOrDefault("maintMargin", "");
                            Object marginAsset = m.getOrDefault("marginAsset", "");
                            Object updateTimeObj = m.get("updateTime");
                            Object updateTimeDisplay = updateTimeObj;
                            if (updateTimeObj instanceof Number) {
                                try {
                                    updateTimeDisplay = new Date(((Number) updateTimeObj).longValue());
                                } catch (Exception ignored) {
                                }
                            }

                            Object[] row = new Object[] {
                                    symbol,
                                    positionSide,
                                    positionAmt,
                                    entryPrice,
                                    breakEvenPrice,
                                    markPrice,
                                    unrealized,
                                    liquidationPrice,
                                    notional,
                                    initialMargin,
                                    maintMargin,
                                    marginAsset,
                                    updateTimeDisplay
                            };
                            positionsTableModel.addRow(row);
                        } catch (Exception ex) {
                            log.warn("Failed to render position row", ex);
                        }
                    }
                }
                positionsTableModel.fireTableDataChanged();
            } else if (type == DataPool.DataType.LEVERAGE || type == DataPool.DataType.COMMISSION_RATE) {
                // 同时展示杠杆率和手续费
                Long leverage = DataPool.getInstance().getLeverage();
                UserCommissionRateResponse commissionRate = DataPool.getInstance().getCommissionRate();
                String commissionText = "手续费: N/A";
                if (commissionRate != null) {
                    commissionText = "手续费: " + " 挂单：" + Double.parseDouble(commissionRate.getMakerCommissionRate())*100 + "%  " +
                            " 吃单：" + Double.parseDouble(commissionRate.getTakerCommissionRate())*100 + "%";
                }
                String text = "杠杆率: " + (leverage != null ? leverage.toString() : "N/A") + "    " + commissionText;
                statusBar.setText(text);
            }
         });
     }

    private void showOrderDetails(AllOrdersResponseInner order) {
        JDialog dlg = new JDialog(this, "Order Details", true);
        dlg.setLayout(new BorderLayout());

        JTextArea ta = new JTextArea();
        ta.setEditable(false);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        StringBuilder sb = new StringBuilder();
        if (order != null) {
            sb.append("Order Details\n");
            sb.append("-----------------------------\n");
            try {
                sb.append("orderId: ").append(order.getOrderId()).append('\n');
            } catch (Exception ignored) {}
            try { sb.append("symbol: ").append(order.getSymbol()).append('\n'); } catch (Exception ignored) {}
            try { sb.append("side: ").append(order.getSide()).append('\n'); } catch (Exception ignored) {}
            try { sb.append("positionSide: ").append(order.getPositionSide()).append('\n'); } catch (Exception ignored) {}
            try { sb.append("status: ").append(order.getStatus()).append('\n'); } catch (Exception ignored) {}
            try { sb.append("executedQty: ").append(order.getExecutedQty()).append('\n'); } catch (Exception ignored) {}
            try { sb.append("origQty: ").append(order.getOrigQty()).append('\n'); } catch (Exception ignored) {}
            try { sb.append("price: ").append(order.getPrice()).append('\n'); } catch (Exception ignored) {}
            try { sb.append("time: ").append(order.getTime()).append('\n'); } catch (Exception ignored) {}
            // append full toString for any extra fields
            try {
                sb.append('\n').append("Raw: ").append(order.toString()).append('\n');
            } catch (Exception ignored) {}
        } else {
            sb.append("No order data available");
        }

        ta.setText(sb.toString());

        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(600, 400));
        dlg.add(sp, BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dlg.dispose());
        btnRow.add(close);
        dlg.add(btnRow, BorderLayout.SOUTH);

        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void showTransientNotification(String title, String message) {
        // Simple lightweight transient popup using JDialog
        JDialog dlg = new JDialog(this);
        dlg.setUndecorated(true);
        dlg.setLayout(new BorderLayout());
        JLabel lbl = new JLabel(String.format("<html><b>%s</b><br/>%s</html>", title, message));
        lbl.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        dlg.add(lbl, BorderLayout.CENTER);
        dlg.pack();
        // position at bottom-right of main frame
        Point loc = getLocationOnScreen();
        int x = loc.x + getWidth() - dlg.getWidth() - 20;
        int y = loc.y + getHeight() - dlg.getHeight() - 40;
        dlg.setLocation(x, y);
        dlg.setAlwaysOnTop(true);
        dlg.setVisible(true);

        // Auto-hide after 5 seconds
        Timer t = new Timer(5000, e -> dlg.dispose());
        t.setRepeats(false);
        t.start();
    }

    // Lightweight pie chart panel that visualizes balances as a pie with a simple legend.
    private static class PieChartPanel extends JPanel {
        private List<FuturesAccountBalanceV2ResponseInner> balances;
        private static final DecimalFormat DF = new DecimalFormat("#,##0.00");

        public void setBalances(List<FuturesAccountBalanceV2ResponseInner> balances) {
            this.balances = balances;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = getWidth();
                int h = getHeight();
                if (balances == null || balances.isEmpty()) {
                    g2.drawString("No balance data", 10, 20);
                    return;
                }

                // compute total excluding zero/negative values
                double total = 0.0;
                Map<String, Double> vals = new HashMap<>();
                for (FuturesAccountBalanceV2ResponseInner b : balances) {
                    try {
                        double v = Double.parseDouble(b.getBalance());
                        if (v > 0.0) {
                            vals.put(b.getAsset(), v);
                            total += v;
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (vals.isEmpty() || total <= 0.0) {
                    g2.drawString("No positive balances to show", 10, 20);
                    return;
                }

                int pieSize = Math.min(w / 2, h - 40);
                int pieX = 10;
                int pieY = 10;

                // Draw pie
                double start = 0.0;
                int i = 0;
                for (Map.Entry<String, Double> e : vals.entrySet()) {
                    double fraction = e.getValue() / total;
                    int angle = (int) Math.round(fraction * 360);
                    g2.setColor(getColorForIndex(i));
                    g2.fillArc(pieX, pieY, pieSize, pieSize, (int) Math.round(start), angle);
                    start += angle;
                    i++;
                }

                // Draw legend to the right
                int legendX = pieX + pieSize + 20;
                int legendY = pieY + 5;
                i = 0;
                for (Map.Entry<String, Double> e : vals.entrySet()) {
                    g2.setColor(getColorForIndex(i));
                    g2.fillRect(legendX, legendY + i * 20, 12, 12);
                    g2.setColor(Color.BLACK);
                    double val = e.getValue();
                    String label = String.format("%s: %s (%.1f%%)", e.getKey(), DF.format(val), val / total * 100.0);
                    g2.drawString(label, legendX + 18, legendY + 12 + i * 20);
                    i++;
                }
            } finally {
                g2.dispose();
            }
        }

        private Color getColorForIndex(int idx) {
            Color[] palette = new Color[]{new Color(0x4e79a7), new Color(0xf28e2b), new Color(0xe15759), new Color(0x76b7b2), new Color(0x59a14f), new Color(0xedc948), new Color(0xb07aa1), new Color(0xff9da7), new Color(0x9c755f), new Color(0xbab0ac)};
            return palette[idx % palette.length];
        }
    }
}


























package com.galaxy.auratrader.ui;

import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.AllOrdersResponseInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.FuturesAccountBalanceV2ResponseInner;
import com.formdev.flatlaf.FlatLightLaf;
import com.galaxy.auratrader.model.DataPool;
import com.galaxy.auratrader.model.DataPoolObserver;
import com.galaxy.auratrader.model.KlineData;
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
import java.util.List;

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
    private JTabbedPane tabbedPane;

    // Orders UI
    private JPanel ordersPanel;
    private JTable ordersTable;
    private DefaultTableModel ordersTableModel;


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
//        // Balance button should be to the left of AI as requested
//        JButton balanceButton = new JButton("Balance");
//        balanceButton.addActionListener(e -> {
//            if (tabbedPane != null) {
//                tabbedPane.setSelectedComponent(balancePanel);
//            }
//        });
//        toolBar.add(balanceButton);
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
        loadButton.addActionListener(e -> loadData());
        controlPanel.add(loadButton);

        topContainer.add(controlPanel, BorderLayout.CENTER);
        add(topContainer, BorderLayout.NORTH);

        // Chart Panel
        chartPanel = new KLineChartPanel();
        // Create tabbed pane and add chart and balance tabs
        tabbedPane = new JTabbedPane();

        // Balance Panel
        balancePanel = new JPanel();
        balancePanel.setLayout(new BoxLayout(balancePanel, BoxLayout.Y_AXIS));
        balancePanel.setBorder(BorderFactory.createTitledBorder("Account Balance"));
        balancePanel.setPreferredSize(new Dimension(200, 0));

        // Orders Panel
        ordersPanel = new JPanel(new BorderLayout());
        ordersPanel.setBorder(BorderFactory.createTitledBorder("All Orders"));
        String[] cols = {"orderId", "symbol", "side", "positionSide", "status", "executedQty", "origQty", "price", "time"};
        ordersTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        ordersTable = new JTable(ordersTableModel);
        JScrollPane ordersScroll = new JScrollPane(ordersTable);
        ordersPanel.add(ordersScroll, BorderLayout.CENTER);

        // Orders control: refresh button
        JButton ordersRefresh = new JButton("刷新订单");
        ordersRefresh.addActionListener(e -> refreshOrders());
        JPanel ordersCtl = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ordersCtl.add(ordersRefresh);
        ordersPanel.add(ordersCtl, BorderLayout.NORTH);

        tabbedPane.addTab("Chart", chartPanel);
        tabbedPane.addTab("Account Balance", balancePanel);
        tabbedPane.addTab("Orders", ordersPanel);

        add(tabbedPane, BorderLayout.CENTER);

        // Note: balancePanel is now part of the tabbed pane

        // Load balance initially
        loadBalance();

        // 自动在主界面第一次显示时触发一次刷新（确保已有默认选择）
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
            }
        });
    }

    private void loadData() {
        String pair = (String) pairComboBox.getSelectedItem();
        String interval = (String) intervalComboBox.getSelectedItem();

        if (pair == null || interval == null) {
            return;
        }

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
                    List<FuturesAccountBalanceV2ResponseInner> balances = get();
                    balancePanel.removeAll();
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
                        balancePanel.add(new JLabel(text));
                    }
                    balancePanel.revalidate();
                    balancePanel.repaint();
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
                balancePanel.removeAll();
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
                    balancePanel.add(new JLabel(text));
                }
                balancePanel.revalidate();
                balancePanel.repaint();
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
                                o.getOrderId(),
                                o.getSymbol(),
                                o.getSide(),
                                o.getPositionSide(),
                                o.getStatus(),
                                o.getExecutedQty(),
                                o.getOrigQty(),
                                o.getPrice(),
                                o.getTime()
                        };
                        ordersTableModel.addRow(row);
                    }
                }
                ordersTableModel.fireTableDataChanged();
            }
        });
    }
}

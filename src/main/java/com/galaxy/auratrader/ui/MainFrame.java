package com.galaxy.auratrader.ui;

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
import java.awt.*;
import java.util.List;

@Component
@Profile("!test")
@Slf4j
public class MainFrame extends JFrame implements DataPoolObserver {

    private final BinanceService binanceService;
    private final IndicatorService indicatorService;
    private KLineChartPanel chartPanel;
    private JComboBox<String> pairComboBox;
    private JComboBox<String> intervalComboBox;
    private JPanel balancePanel;


    public MainFrame(BinanceService binanceService, IndicatorService indicatorService) {
        this.binanceService = binanceService;
        this.indicatorService = indicatorService;
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

        // Top Panel for controls
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        // Pair Selector
        List<String> pairs = binanceService.getPairs();
        pairComboBox = new JComboBox<>(pairs.toArray(new String[0]));
        pairComboBox.addActionListener(e -> loadData());
        controlPanel.add(new JLabel("Pair:"));
        controlPanel.add(pairComboBox);

        // Interval Selector
        String[] intervals = {"1m", "5m", "15m", "1h", "4h", "1d"};
        intervalComboBox = new JComboBox<>(intervals);
        intervalComboBox.addActionListener(e -> loadData());
        controlPanel.add(new JLabel("Interval:"));
        controlPanel.add(intervalComboBox);

        // Load Button
        JButton loadButton = new JButton("刷新");
        loadButton.addActionListener(e -> loadData());
        controlPanel.add(loadButton);

        add(controlPanel, BorderLayout.NORTH);

        // Chart Panel
        chartPanel = new KLineChartPanel();
        add(chartPanel, BorderLayout.CENTER);

        // Balance Panel
        balancePanel = new JPanel();
        balancePanel.setLayout(new BoxLayout(balancePanel, BoxLayout.Y_AXIS));
        balancePanel.setBorder(BorderFactory.createTitledBorder("Account Balance"));
        balancePanel.setPreferredSize(new Dimension(200, 0));
        add(balancePanel, BorderLayout.EAST);

        // Load balance initially
        loadBalance();
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
                    e.printStackTrace();
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
                        String text = String.format("%s: %.2f", balance.getAsset(), Double.parseDouble(balance.getBalance()));
                        balancePanel.add(new JLabel(text));
                    }
                    balancePanel.revalidate();
                    balancePanel.repaint();
                } catch (Exception e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(MainFrame.this, "Error loading balance: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
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
                IndicatorResult ind = indicatorService.computeIndicators(data, 20, 14);
                chartPanel.setIndicators(ind);
            } else if (type == DataPool.DataType.BALANCE) {
                List<FuturesAccountBalanceV2ResponseInner> balances = DataPool.getInstance().getBalances();
                balancePanel.removeAll();
                for (FuturesAccountBalanceV2ResponseInner balance : balances) {
                    String text = String.format("%s: %.2f", balance.getAsset(), Double.parseDouble(balance.getBalance()));
                    balancePanel.add(new JLabel(text));
                }
                balancePanel.revalidate();
                balancePanel.repaint();
            }
        });
    }
}

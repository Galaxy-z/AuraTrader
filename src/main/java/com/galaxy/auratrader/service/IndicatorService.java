package com.galaxy.auratrader.service;

import com.galaxy.auratrader.model.KlineData;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.time.ZonedDateTime;
import java.time.Duration;

/**
 * Service that converts KlineData into a Ta4j BarSeries and computes indicators: MA (SMA), RSI, MACD.
 */
@Service
public class IndicatorService {

    @Data
    @AllArgsConstructor
    public static class IndicatorPoint {
        private Date time;
        private double value;
    }

    @Data
    public static class IndicatorResult {
        private List<IndicatorPoint> ma;
        private List<IndicatorPoint> rsi;
        private List<IndicatorPoint> macd;
        private List<IndicatorPoint> macdSignal;
        private List<IndicatorPoint> macdHistogram;

        public IndicatorResult() {
            ma = new ArrayList<>();
            rsi = new ArrayList<>();
            macd = new ArrayList<>();
            macdSignal = new ArrayList<>();
            macdHistogram = new ArrayList<>();
        }
    }

    /**
     * Build a Ta4j BarSeries from KlineData list. Uses close price and bar end time.
     */
    public BarSeries buildSeries(List<KlineData> klines) {
        BaseBarSeries series = new BaseBarSeries("kline-series");
        for (KlineData k : klines) {
            ZonedDateTime endTime = ZonedDateTime.ofInstant(k.getCloseTime().toInstant(), ZoneId.systemDefault());
            // create a very small duration (use 1 second) as bar duration; ta4j requires a duration
            Bar bar = new BaseBar(Duration.ofSeconds(1), endTime,
                    k.getOpen().doubleValue(),
                    k.getHigh().doubleValue(),
                    k.getLow().doubleValue(),
                    k.getClose().doubleValue(),
                    k.getVolume().doubleValue());
            series.addBar(bar);
        }
        return series;
    }

    /**
     * Compute MA (SMA) with given barCount, RSI with given barCount, and MACD with default fast=12 slow=26 signal=9.
     */
    public IndicatorResult computeIndicators(List<KlineData> klines, int maPeriod, int rsiPeriod) {
        IndicatorResult result = new IndicatorResult();
        if (klines == null || klines.isEmpty()) return result;

        BarSeries series = buildSeries(klines);
        ClosePriceIndicator close = new ClosePriceIndicator(series);

        SMAIndicator sma = new SMAIndicator(close, maPeriod);
        RSIIndicator rsi = new RSIIndicator(close, rsiPeriod);
        MACDIndicator macd = new MACDIndicator(close, 12, 26);

        // MACD signal is typically SMA of MACD line over 9
        SMAIndicator macdSignal = new SMAIndicator(macd, 9);

        for (int i = 0; i < series.getBarCount(); i++) {
            Date time = klines.get(i).getOpenTime();
            // MA
            if (i >= maPeriod - 1) {
                double v = sma.getValue(i).doubleValue();
                result.getMa().add(new IndicatorPoint(time, v));
            } else {
                result.getMa().add(new IndicatorPoint(time, Double.NaN));
            }

            // RSI
            if (i >= rsiPeriod) {
                result.getRsi().add(new IndicatorPoint(time, rsi.getValue(i).doubleValue()));
            } else {
                result.getRsi().add(new IndicatorPoint(time, Double.NaN));
            }

            // MACD
            double macdV = macd.getValue(i).doubleValue();
            double sigV = macdSignal.getValue(i).doubleValue();
            double hist = macdV - sigV;
            result.getMacd().add(new IndicatorPoint(time, macdV));
            result.getMacdSignal().add(new IndicatorPoint(time, sigV));
            result.getMacdHistogram().add(new IndicatorPoint(time, hist));
        }

        return result;
    }
}

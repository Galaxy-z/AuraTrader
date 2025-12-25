package com.galaxy.auratrader.tool;

import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.*;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.api.DerivativesTradingUsdsFuturesWebSocketApi;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Pattern;

import com.galaxy.auratrader.llm.annotation.AIParam;
import com.galaxy.auratrader.llm.annotation.AITool;
import com.galaxy.auratrader.model.DataPool;
import com.galaxy.auratrader.service.BinanceService;
import com.galaxy.auratrader.util.CommonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TradeTool {

    private DataPool dataPool = DataPool.getInstance();

    private ObjectMapper objectMapper = new ObjectMapper();

    private final DerivativesTradingUsdsFuturesRestApi restApi;
    private final DerivativesTradingUsdsFuturesWebSocketStreams webSocketStreams;
    private final DerivativesTradingUsdsFuturesWebSocketApi webSocketApi;

    private final BinanceService binanceService;


    @AITool(name = "order",
            description =
                    """
                            新建订单（交易）
                            
                            提交新订单。
                            
                            * 类型为 `STOP` 的订单可发送参数 `timeInForce`（默认为 `GTC`）。
                            * 类型为 `TAKE_PROFIT` 的订单可发送参数 `timeInForce`（默认为 `GTC`）。
                            * 条件订单将在以下情况触发：
                              * 如果参数 `priceProtect` 设置为 true：
                                * 当价格达到 `stopPrice` 时，"标记价格"与"合约价格"之间的差异率不得超过该交易对的 "triggerProtect" 值
                                * 交易对的 "triggerProtect" 值可通过 `GET /fapi/v1/exchangeInfo` 接口获取
                              * `STOP`、`STOP_MARKET` 类型：
                                * 买入：最新价格（"标记价格"或"合约价格"）>= `stopPrice`
                                * 卖出：最新价格（"标记价格"或"合约价格"）<= `stopPrice`
                              * `TAKE_PROFIT`、`TAKE_PROFIT_MARKET` 类型：
                                * 买入：最新价格（"标记价格"或"合约价格"）<= `stopPrice`
                                * 卖出：最新价格（"标记价格"或"合约价格"）>= `stopPrice`
                              * `TRAILING_STOP_MARKET` 类型：
                                * 买入：订单提交后的最低价格 `<=` `activationPrice`，且最新价格 >= 最低价格 * (1 + `callbackRate`)
                                * 卖出：订单提交后的最高价格 `>=` `activationPrice`，且最新价格 <= 最高价格 * (1 - `callbackRate`)
                            * 对于 `TRAILING_STOP_MARKET` 订单，如果您收到如下错误代码：
                              `{"code": -2021, "msg": "订单将立即触发。"}`
                              表示您发送的参数不满足以下要求：
                              * 买入：`activationPrice` 应小于最新价格。
                              * 卖出：`activationPrice` 应大于最新价格。
                            * 如果 `newOrderRespType` 设置为 `RESULT`：
                              * `MARKET` 订单：将直接返回订单的最终成交结果。
                              * 带有特殊 `timeInForce` 设置的 `LIMIT` 订单：将直接返回订单的最终状态结果（成交或已过期）。
                            * `STOP_MARKET`、`TAKE_PROFIT_MARKET` 且 `closePosition`=`true` 的订单：
                              * 遵循与条件订单相同的规则。
                              * 若触发，将**平掉全部**当前多头仓位（如果是`SELL`订单）或当前空头仓位（如果是`BUY`订单）。
                              * 不可与 `quantity` 参数同时使用
                              * 不可与 `reduceOnly` 参数同时使用
                              * 在双向持仓模式下，`LONG`持仓侧不可用于`BUY`订单，`SHORT`持仓侧不可用于`SELL`订单
                            * `selfTradePreventionMode` 仅在 `timeInForce` 设置为 `IOC`、`GTC` 或 `GTD` 时有效。
                            * 在极端市场条件下，`GTD` 类型订单的自动取消时间相比 `goodTillDate` 设定值可能会有延迟。
                            """,
            category = "trade",
            timeout = 2000)
    public String order(
            @AIParam(name = "symbol", description = "交易对，示例：BTCUSDT") String symbol,
            @AIParam(name = "side", description = "买卖方向，必填。枚举值：SELL 或 BUY", type = "enum", enumValues = {"BUY", "SELL"}) String side,
            @AIParam(name = "positionSide", description = "持仓方向。在单向持仓模式下非必填，默认且仅可填 BOTH；在双向持仓模式下必填且仅可选择 LONG 或 SHORT", type = "enum", enumValues = {"BOTH", "LONG", "SHORT"}, required = false) String positionSide,
            @AIParam(name = "type", description = "订单类型，必填。枚举值：LIMIT, MARKET, STOP, TAKE_PROFIT, STOP_MARKET, TAKE_PROFIT_MARKET, TRAILING_STOP_MARKET", type = "enum", enumValues = {"LIMIT", "MARKET", "STOP", "TAKE_PROFIT", "STOP_MARKET", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"}) String type,
            @AIParam(name = "timeInForce", description = "时间生效类型。可选值如 GTC/IOC/GTD 等；当 timeInForce 为 GTD 时，必须设置 goodTillDate", required = false) String timeInForce,
            @AIParam(name = "reduceOnly", description = "是否仅减仓。非双开模式下默认 false；双开模式下不接受此参数，值示例：true/false", required = false, enumValues = {"true", "false"}) String reduceOnly,
            @AIParam(name = "quantity", description = "委托数量（可选，市价单或某些条件单可能不需要）", required = false) String quantity,
            @AIParam(name = "price", description = "委托价格（可选）。注意：不能与 priceMatch 同时传递", required = false) String price,
            @AIParam(name = "newClientOrderId", description = "用户自定义的订单号，不能在挂单中重复。若空由系统自动赋值。必须匹配正则 ^[\\.A-Z\\:/a-z0-9_-]{1,36}$", required = false) String newClientOrderId,
            @AIParam(name = "newOrderRespType", description = "返回类型，可选：ACK 或 RESULT（默认 ACK）。当为 RESULT 时，某些订单类型会直接返回最终执行结果", type = "enum", enumValues = {"ACK", "RESULT"}, required = false) String newOrderRespType,
            @AIParam(name = "priceMatch", description = "价格匹配方式，可选：OPPONENT/OPPONENT_5/OPPONENT_10/OPPONENT_20/QUEUE/QUEUE_5/QUEUE_10/QUEUE_20；不能与 price 同时传", required = false) String priceMatch,
            @AIParam(name = "selfTradePreventionMode", description = "自成交保护模式。可选：EXPIRE_TAKER/EXPIRE_MAKER/EXPIRE_BOTH，默认 EXPIRE_MAKER。仅在 timeInForce 为 IOC/GTC/GTD 时有效", required = false) String selfTradePreventionMode,
            @AIParam(name = "goodTillDate", description = "当 timeInForce 为 GTD 时必须传入。为自动取消时间, yyyy-MM-dd HH:mm:ss格式", required = false) String goodTillDate
    ) {

        NewOrderRequest request = new NewOrderRequest();

        if (symbol != null) request.setSymbol(symbol);
        if (side != null) {
            try {
                request.setSide(Side.valueOf(side));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid side value\"}";
            }
        }
        if (positionSide == null) positionSide = "BOTH";
        try {
            request.setPositionSide(PositionSide.valueOf(positionSide));
        } catch (IllegalArgumentException ex) {
            return "{\"error\":\"invalid positionSide value\"}";
        }
        if (type != null) request.setType(type);
        if (timeInForce != null) {
            try {
                request.setTimeInForce(TimeInForce.valueOf(timeInForce));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid timeInForce value\"}";
            }
        }
        if (reduceOnly != null) request.setReduceOnly(reduceOnly);
        if (quantity != null) {
            try {
                request.setQuantity(Double.valueOf(quantity));
            } catch (NumberFormatException ex) {
                return "{\"error\":\"invalid quantity\"}";
            }
        }
        if (price != null) {
            try {
                request.setPrice(Double.valueOf(price));
            } catch (NumberFormatException ex) {
                return "{\"error\":\"invalid price\"}";
            }
        }
        if (newClientOrderId != null) request.setNewClientOrderId(newClientOrderId);
        if (newOrderRespType != null) {
            try {
                request.setNewOrderRespType(NewOrderRespType.valueOf(newOrderRespType));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid newOrderRespType\"}";
            }
        } else request.setNewOrderRespType(NewOrderRespType.ACK);
        if (priceMatch != null) {
            try {
                request.setPriceMatch(PriceMatch.valueOf(priceMatch));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid priceMatch\"}";
            }
        }
        if (selfTradePreventionMode != null) {
            try {
                request.setSelfTradePreventionMode(SelfTradePreventionMode.valueOf(selfTradePreventionMode));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid selfTradePreventionMode\"}";
            }
        } else request.setSelfTradePreventionMode(SelfTradePreventionMode.EXPIRE_MAKER);
        Long goodTillDateTimestamp = CommonUtil.dateToUnixTimestampMillis(goodTillDate);
        if (goodTillDate != null) request.setGoodTillDate(goodTillDateTimestamp);
        request.setRecvWindow(500L);

        // Basic validations per API docs
        // priceMatch 和 price 不能同时传
        if (priceMatch != null && price != null) {
            return "{\"error\":\"priceMatch cannot be passed together with price\"}";
        }

        // newClientOrderId 必须匹配正则 ^[\.A-Z\:/a-z0-9_-]{1,36}$
        if (newClientOrderId != null) {
            Pattern p = Pattern.compile("^[\\.A-Z\\:/a-z0-9_-]{1,36}$");
            if (!p.matcher(newClientOrderId).matches()) {
                return "{\"error\":\"newClientOrderId does not match required pattern\"}";
            }
        }

        // 当 timeInForce 为 GTD 时，goodTillDate 必传且必须满足时间范围
        if ("GTD".equals(timeInForce)) {
            if (goodTillDateTimestamp == null) {
                return "{\"error\":\"goodTillDate is required when timeInForce=GTD\"}";
            }
            long now = System.currentTimeMillis();
            if (goodTillDateTimestamp <= now + 600_000L || goodTillDateTimestamp >= 253402300799000L) {
                return "{\"error\":\"goodTillDate must be > now+600s and < 253402300799000\"}";
            }
        }

        try {
            ApiResponse<NewOrderResponse> resp = restApi.newOrder(request);
            return objectMapper.writeValueAsString(resp.getData());
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }

    }

    @AITool(
            name = "orderAlgo",
            description = """
                    
                    提交新的条件单。
                    
                    * 条件订单将在以下情况触发：
                      * 如果参数 `priceProtect` 设置为 true：
                        * 当价格达到 `triggerPrice` 时，"标记价格"与"合约价格"之间的差异率不得超过该交易对的 "triggerProtect" 值
                        * 交易对的 "triggerProtect" 值可通过 `GET /fapi/v1/exchangeInfo` 接口获取
                      * `STOP`、`STOP_MARKET` 类型：
                        * 买入：最新价格（"标记价格"或"合约价格"）>= `triggerPrice`
                        * 卖出：最新价格（"标记价格"或"合约价格"）<= `triggerPrice`
                      * `TAKE_PROFIT`、`TAKE_PROFIT_MARKET` 类型：
                        * 买入：最新价格（"标记价格"或"合约价格"）<= `triggerPrice`
                        * 卖出：最新价格（"标记价格"或"合约价格"）>= `triggerPrice`
                      * `TRAILING_STOP_MARKET` 类型：
                        * 买入：订单提交后的最低价格 <= `activationPrice`，且最新价格 >= 最低价格 * (1 + `callbackRate`)
                        * 卖出：订单提交后的最高价格 >= `activationPrice`，且最新价格 <= 最高价格 * (1 - `callbackRate`)
                    * 对于 `TRAILING_STOP_MARKET` 订单，如果您收到如下错误代码：
                      `{"code": -2021, "msg": "订单将立即触发。"}`
                      表示您发送的参数不满足以下要求：
                      * 买入：`activationPrice` 应小于最新价格。
                      * 卖出：`activationPrice` 应大于最新价格。
                    * `STOP_MARKET`、`TAKE_PROFIT_MARKET` 且 `closePosition`=`true` 的订单：
                      * 遵循与条件订单相同的规则。
                      * 若触发，将**平掉全部**当前多头仓位（如果是`SELL`订单）或当前空头仓位（如果是`BUY`订单）。
                      * 不可与 `quantity` 参数同时使用
                      * 不可与 `reduceOnly` 参数同时使用
                      * 在双向持仓模式下，`LONG`持仓侧不可用于`BUY`订单，`SHORT`持仓侧不可用于`SELL`订单
                    * `selfTradePreventionMode` 仅在 `timeInForce` 设置为 `IOC`、`GTC` 或 `GTD` 时有效。
                    """,
            category = "trade",
            timeout = 2000
    )
    public String orderAlgo(
            @AIParam(name = "symbol", description = "交易对，示例：BTCUSDT") String symbol,
            @AIParam(name = "side", description = "买卖方向，必填。枚举值：SELL 或 BUY", type = "enum", enumValues = {"BUY", "SELL"}) String side,
            @AIParam(name = "positionSide", description = "持仓方向。在单向持仓模式下非必填，默认且仅可填 BOTH；在双向持仓模式下必填且仅可选择 LONG 或 SHORT", type = "enum", enumValues = {"BOTH", "LONG", "SHORT"}, required = false) String positionSide,
            @AIParam(name = "type", description = "订单类型，必填。枚举值：LIMIT, MARKET, STOP, TAKE_PROFIT, STOP_MARKET, TAKE_PROFIT_MARKET, TRAILING_STOP_MARKET", type = "enum", enumValues = {"LIMIT", "MARKET", "STOP", "TAKE_PROFIT", "STOP_MARKET", "TAKE_PROFIT_MARKET", "TRAILING_STOP_MARKET"}) String type,
            @AIParam(name = "timeInForce", description = "时间生效类型。默认GTC；当 timeInForce 为 GTD 时，必须设置 goodTillDate", required = false, type = "enum", enumValues = {"IOC", "GTC", "FOK", "GTX", "GTC"}) String timeInForce,
            @AIParam(name = "quantity", description = "委托数量（可选，市价单或某些条件单可能不需要）", required = false) String quantity,
            @AIParam(name = "price", description = "委托价格（可选）。注意：不能与 priceMatch 同时传", required = false) String price,
            @AIParam(name = "triggerPrice", description = "触发价格", required = false) String triggerPrice,
            @AIParam(name = "workingType", description = "触发类型: MARK_PRICE(标记价格), CONTRACT_PRICE(合约最新价). 默认 CONTRACT_PRICE", type = "enum", enumValues = {"MARK_PRICE", "CONTRACT_PRICE"}) String workingType,
            @AIParam(name = "priceMatch", description = "价格匹配方式，可选：OPPONENT/OPPONENT_5/OPPONENT_10/OPPONENT_20/QUEUE/QUEUE_5/QUEUE_10/QUEUE_20；不能与 price 同时传", required = false) String priceMatch,
            @AIParam(name = "closePosition", description = "是否为触发平仓单; true/false，触发后全部平仓，仅支持STOP_MARKET和TAKE_PROFIT_MARKET；不与quantity合用；自带只平仓效果，不与reduceOnly 合用", required = false, type = "enum", enumValues = {"true", "false"}) String closePosition,
            @AIParam(name = "priceProtect", description = "是否开启条件单触发保护，值示例：true/false", required = false, type = "enum", enumValues = {"true", "false"}) String priceProtect,
            @AIParam(name = "reduceOnly", description = "是否仅减仓。非双开模式下默认 false；双开模式下不接受此参数，值示例：true/false", required = false) String reduceOnly,
            @AIParam(name = "activationPrice", description = "追踪止损激活价格，仅TRAILING_STOP_MARKET 需要此参数, 默认为下单当前市场价格(支持不同workingType)", required = false)
            String activationPrice,
            @AIParam(name = "callbackRate", description = "追踪止损回调比例，可取值范围[0.1, 10],其中 1代表1% ,仅TRAILING_STOP_MARKET 需要此参数", required = false) String callbackRate,
            @AIParam(name = "clientAlgoId", description = "用户自定义的订单号，不能在挂单中重复。若空由系统自动赋值。必须匹配正则 ^[\\.A-Z\\:/a-z0-9_-]{1,36}$", required = false) String clientAlgoId,
//            @AIParam(name = "newOrderRespType", description = "返回类型，可选：ACK 或 RESULT（默认 ACK）。当为 RESULT 时，某些订单类型会直接返回最终执行结果", type = "enum", enumValues = {"ACK", "RESULT"}, required = false) String newOrderRespType,
            @AIParam(name = "selfTradePreventionMode", description = "自成交保护模式。可选：EXPIRE_TAKER/EXPIRE_MAKER/EXPIRE_BOTH，默认 NONE。仅在 timeInForce 为 IOC/GTC/GTD 时有效", required = false, type = "enum", enumValues = {"EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH", "NONE"}) String selfTradePreventionMode,
            @AIParam(name = "goodTillDate", description = "当 timeInForce 为 GTD 时必须传入。为自动取消时间, yyyy-MM-dd HH:mm:ss格式", required = false) String goodTillDate
    ) {

        NewAlgoOrderRequest request = new NewAlgoOrderRequest();
        request.setAlgoType("CONDITIONAL");
        if (symbol != null) request.setSymbol(symbol);
        if (side != null) {
            try {
                request.setSide(Side.valueOf(side));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid side value\"}";
            }
        }
        if (positionSide == null) positionSide = "BOTH";
        try {
            request.setPositionSide(PositionSide.valueOf(positionSide));
        } catch (IllegalArgumentException ex) {
            return "{\"error\":\"invalid positionSide value\"}";
        }
        if (type != null) request.setType(type);
        if (timeInForce != null) {
            try {
                request.setTimeInForce(TimeInForce.valueOf(timeInForce));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid timeInForce value\"}";
            }
        }
        if (reduceOnly != null) request.setReduceOnly(reduceOnly);
        if (quantity != null) {
            try {
                request.setQuantity(Double.valueOf(quantity));
            } catch (NumberFormatException ex) {
                return "{\"error\":\"invalid quantity\"}";
            }
        }
        if (price != null) {
            try {
                request.setPrice(Double.valueOf(price));
            } catch (NumberFormatException ex) {
                return "{\"error\":\"invalid price\"}";
            }
        }
        if (triggerPrice != null) {
            try {
                request.setTriggerPrice(Double.valueOf(triggerPrice));
            } catch (NumberFormatException ex) {
                return "{\"error\":\"invalid triggerPrice\"}";
            }
        }
        if (clientAlgoId != null) request.setClientAlgoId(clientAlgoId);

        // Map workingType if provided
        if (workingType != null) {
            try {
                // SDK may define a WorkingType enum; try to use it
                request.setWorkingType(WorkingType.valueOf(workingType));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid workingType\"}";
            }
        }

        // Map closePosition and priceProtect (may be string flags in SDK)
        if (closePosition != null) request.setClosePosition(closePosition);
        if (priceProtect != null) request.setPriceProtect(priceProtect);

        // Map activationPrice (used by TRAILING_STOP_MARKET) and callbackRate
        if (activationPrice != null) {
            try {
                request.setActivationPrice(Double.valueOf(activationPrice));
            } catch (NumberFormatException ex) {
                return "{\"error\":\"invalid activationPrice\"}";
            }
        }
        if (callbackRate != null) {
            try {
                request.setCallbackRate(Double.valueOf(callbackRate));
            } catch (NumberFormatException ex) {
                return "{\"error\":\"invalid callbackRate\"}";
            }
        }


        if (priceMatch != null) {
            try {
                request.setPriceMatch(PriceMatch.valueOf(priceMatch));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid priceMatch\"}";
            }
        }
        if (selfTradePreventionMode != null) {
            try {
                request.setSelfTradePreventionMode(SelfTradePreventionMode.valueOf(selfTradePreventionMode));
            } catch (IllegalArgumentException ex) {
                return "{\"error\":\"invalid selfTradePreventionMode\"}";
            }
        } else request.setSelfTradePreventionMode(SelfTradePreventionMode.EXPIRE_MAKER);
        Long goodTillDateTimestamp = CommonUtil.dateToUnixTimestampMillis(goodTillDate);
        if (goodTillDate != null) request.setGoodTillDate(goodTillDateTimestamp);
        request.setRecvWindow(500L);

        // Basic validations per API docs
        if (priceMatch != null && price != null) {
            return "{\"error\":\"priceMatch cannot be passed together with price\"}";
        }

        if (clientAlgoId != null) {
            Pattern p = Pattern.compile("^[\\.A-Z\\:/a-z0-9_-]{1,36}$");
            if (!p.matcher(clientAlgoId).matches()) {
                return "{\"error\":\"newClientOrderId does not match required pattern\"}";
            }
        }

        if ("GTD".equals(timeInForce)) {
            if (goodTillDateTimestamp == null) {
                return "{\"error\":\"goodTillDate is required when timeInForce=GTD\"}";
            }
            long now = System.currentTimeMillis();
            if (goodTillDateTimestamp <= now + 600_000L || goodTillDateTimestamp >= 253402300799000L) {
                return "{\"error\":\"goodTillDate must be > now+600s and < 253402300799000\"}";
            }
        }

        try {
            ApiResponse<NewAlgoOrderResponse> resp = restApi.newAlgoOrder(request);
            return objectMapper.writeValueAsString(resp.getData());
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }

    }

    @AITool(
            name = "getCurrentPosition",
            description = "获取当前交易对的持仓信息",
            category = "trade",
            timeout = 2000
    )
    public String getCurrentPosition(@AIParam(name = "symbol", description = "交易对，示例：BTCUSDT") String symbol) {
        List<PositionInformationV3ResponseInner> positions = binanceService.getPositions(symbol);
        try {
            return objectMapper.writeValueAsString(positions);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }


    /**
     * 名称	类型	是否必需	描述
     * algoid	LONG	NO	系统订单号
     * clientalgoid	STRING	NO	用户自定义的订单号
     *
     * @return
     */
    @AITool(
            name = "cancelAlgoOrder",
            description = "取消条件单；algoid 与 clientalgoid 必须至少发送一个",
            category = "trade",
            timeout = 2000
    )
    public String cancelAlgoOrder(
            @AIParam(name = "algoId", description = "系统订单号", required = false, type = "number") Long algoId,
            @AIParam(name = "clientAlgoId", description = "用户自定义的订单号", required = false) String clientAlgoId
    ) {
        ApiResponse<CancelAlgoOrderResponse> response = restApi.cancelAlgoOrder(algoId, clientAlgoId, 500L);
        try {
            return objectMapper.writeValueAsString(response.getData());
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }


    /**
     * 名称	类型	是否必需	描述
     * symbol	STRING	YES	交易对
     * orderId	LONG	NO	系统订单号
     * origClientOrderId	STRING	NO	用户自定义的订单号
     *
     * @return
     */
    @AITool(
            name = "cancelOrder",
            description = "取消订单；orderId 与 origClientOrderId 必须至少发送一个",
            category = "trade",
            timeout = 2000
    )
    public String cancelOrder(
            @AIParam(name = "symbol", description = "交易对，示例：BTCUSDT") String symbol,
            @AIParam(name = "orderId", description = "系统订单号", required = false, type = "number") Long orderId,
            @AIParam(name = "origClientOrderId", description = "用户自定义的订单号", required = false) String origClientOrderId
    ) {
        ApiResponse<CancelOrderResponse> response = restApi.cancelOrder(symbol, orderId, origClientOrderId, 500L);
        try {
            return objectMapper.writeValueAsString(response.getData());
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

}

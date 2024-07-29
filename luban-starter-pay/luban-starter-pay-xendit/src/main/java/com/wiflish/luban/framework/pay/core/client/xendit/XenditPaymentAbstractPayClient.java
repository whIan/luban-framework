package com.wiflish.luban.framework.pay.core.client.xendit;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wiflish.luban.framework.pay.core.client.dto.order.PayOrderRespDTO;
import com.wiflish.luban.framework.pay.core.client.dto.order.PayOrderUnifiedReqDTO;
import com.wiflish.luban.framework.pay.core.client.dto.refund.PayRefundRespDTO;
import com.wiflish.luban.framework.pay.core.client.dto.refund.PayRefundUnifiedReqDTO;
import com.wiflish.luban.framework.pay.core.client.dto.transfer.PayTransferRespDTO;
import com.wiflish.luban.framework.pay.core.client.dto.transfer.PayTransferUnifiedReqDTO;
import com.wiflish.luban.framework.pay.core.client.impl.AbstractPayClient;
import com.wiflish.luban.framework.pay.core.enums.order.PayOrderDisplayModeEnum;
import com.wiflish.luban.framework.pay.core.enums.order.PayOrderStatusRespEnum;
import com.wiflish.luban.framework.pay.core.enums.transfer.PayTransferTypeEnum;
import com.wiflish.luban.framework.pay.xendit.dto.payment.*;
import com.xendit.XenditClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static cn.hutool.core.date.DatePattern.NORM_DATETIME_FORMATTER;
import static com.wiflish.luban.framework.common.exception.enums.GlobalErrorCodeConstants.INVOKER_ERROR;
import static com.wiflish.luban.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * Xendit payment支付，统一的API.
 *
 * @author wiflish
 * @since 2024-07-26
 */
@Slf4j
public abstract class XenditPaymentAbstractPayClient extends AbstractPayClient<XenditPayClientConfig> {
    private static final String XENDIT_PAYMENT_REQUEST_URL = "https://api.xendit.co/payment_requests";
    private static final String XENDIT_REFUND_URL = "https://api.xendit.co/refunds";
    protected XenditClient xenditClient;
    protected RestTemplate restTemplate;

    public XenditPaymentAbstractPayClient(Long channelId, String channelCode, XenditPayClientConfig config) {
        super(channelId, channelCode, config);
    }

    @Override
    protected void doInit() {
        xenditClient = new XenditClient.Builder().setApikey(config.getApiKey()).build();
        restTemplate = SpringUtil.getBean(RestTemplate.class);
    }

    @Override
    protected PayOrderRespDTO doUnifiedOrder(PayOrderUnifiedReqDTO reqDTO) throws Throwable {

        PaymentRequestDTO requestDTO = new PaymentRequestDTO();

        requestDTO.setCurrency(reqDTO.getCurrency())
                .setAmount(reqDTO.getPrice() / 100).setReferenceId(reqDTO.getOutTradeNo())
                .setPaymentMethod(getPaymentMethod(reqDTO))
                .setChannelPropertiesDTO(channelProperties(reqDTO));

        PaymentResponseDTO paymentResponseDTO = null;
        try {
            paymentResponseDTO = httpRequest(XENDIT_PAYMENT_REQUEST_URL, HttpMethod.POST, config.getApiKey(), requestDTO, PaymentResponseDTO.class);
        } catch (HttpClientErrorException e) {
            log.error("发起支付调用失败, 渠道: xendit, req: {}, resp: {}", JSON.toJSONString(requestDTO), JSON.toJSONString(paymentResponseDTO), e);
            String responseBodyAsString = e.getResponseBodyAsString();
            JSONObject jsonObject = JSON.parseObject(responseBodyAsString);
            PayOrderRespDTO payOrderRespDTO = PayOrderRespDTO.waitingOf(null, null, reqDTO.getOutTradeNo(), responseBodyAsString);
            payOrderRespDTO.setChannelErrorCode(jsonObject.getString("error_code")).setChannelErrorMsg(jsonObject.getString("message"));
            return payOrderRespDTO;
        }

        PayOrderRespDTO respDTO = new PayOrderRespDTO();
        respDTO.setChannelOrderNo(paymentResponseDTO.getId());
        if (paymentResponseDTO.getActions() != null && !paymentResponseDTO.getActions().isEmpty()) {
            respDTO.setDisplayMode(PayOrderDisplayModeEnum.IFRAME.getMode());
            PaymentActionDTO paymentActionDTO = paymentResponseDTO.getActions().stream().filter(PaymentActionDTO::isMobileUrl).findFirst().orElse(null);
            if (paymentActionDTO == null) {
                paymentActionDTO = paymentResponseDTO.getActions().stream().filter(PaymentActionDTO::isWebUrl).findFirst().orElse(null);
            }
            respDTO.setDisplayContent(paymentActionDTO == null ? null : paymentActionDTO.getUrl());
        }

        return respDTO;
    }

    protected abstract PaymentMethodDTO getPaymentMethod(PayOrderUnifiedReqDTO reqDTO);

    abstract protected ChannelPropertiesDTO channelProperties(PayOrderUnifiedReqDTO reqDTO);


    @Override
    protected PayOrderRespDTO doParseOrderNotify(Map<String, String> params, String body) throws Throwable {
        // 1.校验请求合法性
        // 2.幂等处理
        // 3.处理 body
        Map<String, String> bodyObj = HttpUtil.decodeParamMap(body, StandardCharsets.UTF_8);
        Integer status = parseStatus(bodyObj.get("status"));
        Assert.notNull(status, (Supplier<Throwable>) () -> {
            throw new IllegalArgumentException(StrUtil.format("body({}) 的 trade_status 不正确", body));
        });
        return PayOrderRespDTO.of(status, bodyObj.get("external_id"), bodyObj.get("user_id"), parseTime(params.get("paid_at")),
                bodyObj.get("out_trade_no"), body);
    }

    private static Integer parseStatus(String tradeStatus) {
        return Objects.equals("PAID", tradeStatus) ? PayOrderStatusRespEnum.SUCCESS.getStatus()
                : Objects.equals("EXPIRED", tradeStatus) ? PayOrderStatusRespEnum.CLOSED.getStatus() : null;
    }

    protected LocalDateTime parseTime(String str) {
        return LocalDateTimeUtil.parse(str, NORM_DATETIME_FORMATTER);
    }

    @Override
    protected PayOrderRespDTO doGetOrder(String outTradeNo) throws Throwable {
        return null;
    }

    @Override
    protected PayRefundRespDTO doUnifiedRefund(PayRefundUnifiedReqDTO reqDTO) {
        PaymentRefundDTO paymentRefundDTO = null;
        try {
            paymentRefundDTO = httpRequest(XENDIT_REFUND_URL, HttpMethod.POST, config.getApiKey(), reqDTO, PaymentRefundDTO.class);
        } catch (HttpClientErrorException e) {
            log.error("发起退款调用失败, 渠道: xenditInvoice , req: {}, resp: {}", JSON.toJSONString(reqDTO), JSON.toJSONString(paymentRefundDTO), e);
            String responseBodyAsString = e.getResponseBodyAsString();
            JSONObject jsonObject = JSON.parseObject(responseBodyAsString);
            PayRefundRespDTO payRefundRespDTO = PayRefundRespDTO.failureOf(reqDTO.getOutRefundNo(), responseBodyAsString);
            payRefundRespDTO.setChannelErrorCode(jsonObject.getString("error_code")).setChannelErrorMsg(jsonObject.getString("message"));
            return payRefundRespDTO;
        }

        return PayRefundRespDTO.waitingOf(paymentRefundDTO.getId(), reqDTO.getOutRefundNo(), paymentRefundDTO);
    }

    @Override
    protected PayRefundRespDTO doParseRefundNotify(Map<String, String> params, String body) throws Throwable {
        return null;
    }

    @Override
    protected PayRefundRespDTO doGetRefund(String outTradeNo, String outRefundNo) throws Throwable {
        return null;
    }

    @Override
    protected PayTransferRespDTO doUnifiedTransfer(PayTransferUnifiedReqDTO reqDTO) throws Throwable {
        return null;
    }

    @Override
    protected PayTransferRespDTO doGetTransfer(String outTradeNo, PayTransferTypeEnum type) throws Throwable {
        return null;
    }

    /**
     * API 请求
     *
     * @param url       请求 url
     * @param apiKey    签名 key
     * @param req       对应请求的请求参数
     * @param respClass 对应请求的响应 class
     * @param <Req>     每个请求的请求结构 Req DTO
     * @param <Resp>    每个请求的响应结构 Resp DTO
     */
    private <Req, Resp> Resp httpRequest(String url, HttpMethod httpMethod, String apiKey, Req req, Class<Resp> respClass) {
        // 请求头
        HttpHeaders headers = getHeaders(apiKey);
        Object requestObj = null;
        if (req instanceof PaymentRequestDTO xenditReq) {
            headers.add("idempotency-key", UUID.fastUUID().toString());

            requestObj = JSON.toJSONString(xenditReq);
        } else if (req instanceof PayRefundUnifiedReqDTO xenditReq) {
            headers.add("idempotency-key", UUID.fastUUID().toString());

            Map<String, Object> params = new HashMap<>();
            params.put("reference_id", xenditReq.getOutTradeNo());
            params.put("invoice_id", xenditReq.getChannelOrderNo());
            params.put("amount", xenditReq.getRefundPrice() / 100);
            params.put("currency", "IDR"); //FIXME 先写死, 后续改为读系统配置.
            requestObj = JSON.toJSONString(params);
        }

        // 发送请求
        log.debug("[xenditRequest][请求参数({})]", requestObj);
        HttpEntity<Object> requestEntity = new HttpEntity<>(requestObj, headers);
        ResponseEntity<String> responseEntity = restTemplate.exchange(url, httpMethod, requestEntity, String.class);
        log.debug("[xenditRequest][响应结果({})]", responseEntity);
        // 处理响应
        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            throw exception(INVOKER_ERROR);
        }
        return JSON.parseObject(responseEntity.getBody(), respClass);
    }

    private static HttpHeaders getHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();

        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBasicAuth(apiKey, "");

        return headers;
    }
}
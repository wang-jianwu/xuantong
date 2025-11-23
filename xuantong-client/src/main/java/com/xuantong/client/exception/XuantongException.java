package com.xuantong.client.exception;

/**
 * 配置客户端异常
 */
public class XuantongException extends RuntimeException {

    public XuantongException() {
        super();
    }

    public XuantongException(String message) {
        super(message);
    }

    public XuantongException(String message, Throwable cause) {
        super(message, cause);
    }

    public XuantongException(Throwable cause) {
        super(cause);
    }
}
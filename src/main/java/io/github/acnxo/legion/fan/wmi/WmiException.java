package io.github.acnxo.legion.fan.wmi;

/**
 * WMI 桥接层的统一受检异常。
 *
 * <p>覆盖三类失败：拒绝访问（未提权）、WMI 方法调用出错、返回值解析失败。
 */
public class WmiException extends Exception {

    /** 序列化契约：本异常仅在本进程内传递，不参与持久化或跨进程序列化 */
    private static final long serialVersionUID = 1L;

    public WmiException(String message) {
        super(message);
    }

    public WmiException(String message, Throwable cause) {
        super(message, cause);
    }
}

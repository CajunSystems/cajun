package com.cajunsystems;

/**
 * Unchecked exception thrown when a reply fails.
 */
public class ReplyException extends RuntimeException {
    
    public ReplyException(String message) {
        super(message);
    }
    
    public ReplyException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public ReplyException(Throwable cause) {
        super(cause);
    }
}

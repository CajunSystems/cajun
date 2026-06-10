package com.cajunsystems;

public interface Receiver<Message> {

    Receiver<Message> accept(Message message);
}

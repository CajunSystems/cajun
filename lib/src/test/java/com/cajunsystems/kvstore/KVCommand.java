package com.cajunsystems.kvstore;

import java.io.Serializable;

public sealed interface KVCommand {
    record Put(String key, String value) implements KVCommand, Serializable {}
    record Get(String key) implements KVCommand, Serializable {}
    record Delete(String key) implements KVCommand, Serializable {}
}

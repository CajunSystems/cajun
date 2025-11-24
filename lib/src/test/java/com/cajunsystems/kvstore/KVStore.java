package com.cajunsystems.kvstore;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.StatefulHandler;

import java.util.Map;

public class KVStore implements StatefulHandler<Map<String, String>, KVCommand> {

    @Override
    public Map<String, String> receive(KVCommand kvCommand, Map<String, String> state, ActorContext context) {
        switch (kvCommand) {
            case KVCommand.Put put -> {
                System.out.println(STR."PUT: \{put.key()} -> \{put.value()}");
                state.put(put.key(), put.value());
            }
            case KVCommand.Get get -> {
                System.out.println(STR."GET: \{get.key()}");
                System.out.println(context.getSender().isEmpty());
                context.getSender().ifPresent(sender -> {
                    System.out.println("I am here");
                    sender.tell(state.get(get.key()));
                });
            }
            case KVCommand.Delete delete -> {
                System.out.println(STR."DELETE: \{delete.key()}");
                state.remove(delete.key());
            }
        }
        return state;
    }
}

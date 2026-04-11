package com.cajunsystems.examples.callcenter;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.examples.callcenter.actors.WorkItemHandler;
import com.cajunsystems.examples.callcenter.messages.WorkItemMessage;
import com.cajunsystems.examples.callcenter.messages.AgentMessage;
import com.cajunsystems.examples.callcenter.states.WorkItemState;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelephonyServer {

    private static final Logger logger = LoggerFactory.getLogger(TelephonyServer.class);
    
    private final ActorSystem actorSystem;
    private final Pid queueActor;
    private final int port;

    public TelephonyServer(ActorSystem actorSystem, Pid queueActor, int port) {
        this.actorSystem = actorSystem;
        this.queueActor = queueActor;
        this.port = port;
    }

    public void start() {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        }).start(port);

        logger.info("Telephony Webhook Server started on port {}", port);

        // 1. Incoming Call Webhook
        app.post("/twilio/incoming", this::handleIncomingCall);

        // 2. Call Status Webhook (e.g., caller hangs up)
        app.post("/twilio/status", this::handleCallStatus);

        // 3. Transfer Webhook (Simulating an Agent pressing 'Transfer' in their dashboard)
        app.post("/twilio/transfer/cold", ctx -> {
            String agentId = ctx.queryParam("AgentId");
            if (agentId != null) {
                logger.info("Telephony: Received COLD transfer request for agent {}", agentId);
                actorSystem.tell(new Pid(agentId, actorSystem), new AgentMessage.TransferCold());
            }
            ctx.status(200);
        });

        app.post("/twilio/transfer/warm", ctx -> {
            String agentId = ctx.queryParam("AgentId");
            String targetAgentId = ctx.queryParam("TargetAgentId");
            if (agentId != null && targetAgentId != null) {
                logger.info("Telephony: Received WARM transfer request from agent {} to {}", agentId, targetAgentId);
                actorSystem.tell(new Pid(agentId, actorSystem), new AgentMessage.TransferWarm(new Pid(targetAgentId, actorSystem)));
            }
            ctx.status(200);
        });
    }

    private void handleIncomingCall(Context ctx) {
        String callSid = ctx.formParam("CallSid");
        String from = ctx.formParam("From");

        logger.info("Received incoming call webhook for CallSid: {} from: {}", callSid, from);

        if (callSid == null || from == null) {
            // Provide a mock ID for testing with basic curl requests
            callSid = callSid != null ? callSid : "mock-twl-" + System.currentTimeMillis();
            from = from != null ? from : "+1-202-555-MOCK";
        }

        // Spawn a new WorkItemActor tailored for this specific call!
        // We use the Twilio CallSid as the Actor ID so we can strictly address it later.
        Pid callActorPid = actorSystem.statefulActorOf(new WorkItemHandler(queueActor), new WorkItemState(from))
                .withId(callSid)
                .spawn();

        // Instruct the actor to start its lifecycle (meaning it will send Enqueue to the Queue)
        actorSystem.tell(callActorPid, new WorkItemMessage.StartCall(callSid));

        // Return TwiML telling Twilio to hold the caller in the queue while the Actors process the assignment
        String twiml = "<Response>\n" +
                "  <Say>Please wait while we connect you to the next available Cajun agent.</Say>\n" +
                "  <Enqueue>CajunSupportQueue</Enqueue>\n" +
                "</Response>";
        
        ctx.contentType("text/xml").result(twiml);
    }

    private void handleCallStatus(Context ctx) {
        String callSid = ctx.formParam("CallSid");
        String callStatus = ctx.formParam("CallStatus");

        if (callSid != null && "completed".equals(callStatus)) {
            logger.info("Call {} has completed. Telling WorkItem actor to shut down.", callSid);
            // Since we tied the Actor ID to the CallSid, we can address it directly without keeping a map!
            actorSystem.tell(new Pid(callSid, actorSystem), new WorkItemMessage.EndCall());
        }
        ctx.status(200);
    }
}

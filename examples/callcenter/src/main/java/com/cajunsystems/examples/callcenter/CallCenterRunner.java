package com.cajunsystems.examples.callcenter;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.examples.callcenter.actors.*;
import com.cajunsystems.examples.callcenter.messages.*;
import com.cajunsystems.examples.callcenter.states.*;

public class CallCenterRunner {
    public static void main(String[] args) throws Exception {
        ActorSystem system = new ActorSystem();

        System.out.println("====== Starting Cajun Call Center Example ======");

        // 1. Spawn the Queue Actor
        Pid queueActor = system.statefulActorOf(new QueueHandler(), new QueueState())
                .withId("support-queue")
                .spawn();

        // 2. Spawn Agent Actors and Register them with the Queue
        Pid agent1 = system.statefulActorOf(new AgentHandler(queueActor), new AgentState("Alice"))
                .withId("agent-alice")
                .spawn();
                
        Pid agent2 = system.statefulActorOf(new AgentHandler(queueActor), new AgentState("Bob"))
                .withId("agent-bob")
                .spawn();
                
        Pid agent3 = system.statefulActorOf(new AgentHandler(queueActor), new AgentState("Charlie"))
                .withId("agent-charlie")
                .spawn();

        system.tell(queueActor, new QueueMessage.RegisterAgent(agent1, "Alice"));
        system.tell(queueActor, new QueueMessage.RegisterAgent(agent2, "Bob"));
        system.tell(queueActor, new QueueMessage.RegisterAgent(agent3, "Charlie"));

        // Start the Twilio Webhook Edge Server
        TelephonyServer server = new TelephonyServer(system, queueActor, 7001);
        server.start();

        System.out.println("====== Setup Complete! Listening for Webhooks on :7001 ======");
        System.out.println("-> You can run internal tests via CURL:");
        System.out.println("   curl -X POST http://localhost:7001/twilio/incoming -d 'CallSid=C123&From=+12345'");
        System.out.println("");

        // demonstrating a COLD TRANSFER (Re-queue with Priority)
        // 1. Someone calls in
        System.out.println("--- Starting Cold Transfer Demo ---");
        Pid call1 = system.statefulActorOf(new WorkItemHandler(queueActor), new WorkItemState("+1-555-COLD"))
                .withId("call-1")
                .spawn();
        system.tell(call1, new WorkItemMessage.StartCall("call-1"));

        Thread.sleep(1500); // Wait for Alice to pick up

        // 2. Alice transfers it back to queue
        System.out.println(">>> SIMULATION: Alice is transferring Call-1 back to queue...");
        system.tell(agent1, new AgentMessage.TransferCold());

        Thread.sleep(3000); // Wait for Bob to pick it up and handle it for a bit

        // demonstrating a WARM TRANSFER (Agent to Agent)
        System.out.println("--- Starting Warm Transfer Demo ---");
        System.out.println(">>> SIMULATION: Bob is transferring Call-1 directly to Charlie...");
        system.tell(agent2, new AgentMessage.TransferWarm(agent3));

        Thread.sleep(5000); 

        // 3. Keep the app alive for webhooks
        System.out.println("====== Simulation Sequences Finished. System remaining active for manual webhooks. ======");
        
        // Let the system run for 45s to see multiple rotations
        Thread.sleep(45000);

        system.shutdown();
        System.out.println("====== Shutdown Complete ======");
    }
    
}

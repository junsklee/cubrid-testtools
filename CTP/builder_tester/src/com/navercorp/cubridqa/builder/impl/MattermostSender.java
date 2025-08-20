package com.navercorp.cubridqa.builder.impl;

import com.navercorp.cubridqa.builder.interfaces.Sender;
import org.json.JSONObject;

public class MattermostSender implements Sender {
    @Override
    public void send(JSONObject data) {
        // Implementation for sending data to Mattermost will be added here.
        // For now, it's a placeholder.
        System.out.println("Sending to Mattermost: " + data.toString());
    }
}

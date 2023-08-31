package com.notion.telegrambot.service;

import lombok.Getter;

@Getter
public class Session {
    private final String state;
    private final Long created;

    public Session(String state) {
        this.state = state;
        created = System.currentTimeMillis();
    }
}

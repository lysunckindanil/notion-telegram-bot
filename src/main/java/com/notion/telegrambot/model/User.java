package com.notion.telegrambot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    private long chatId;
    private boolean active = false;
    private long interval;
    @Column(length = 8192)
    private String notions;

}

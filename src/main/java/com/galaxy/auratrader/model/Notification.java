package com.galaxy.auratrader.model;

import java.util.Date;

public class Notification {
    private final Date time;
    private final String title;
    private final String message;

    public Notification(Date time, String title, String message) {
        this.time = time;
        this.title = title;
        this.message = message;
    }

    public Date getTime() {
        return time;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "Notification{" +
                "time=" + time +
                ", title='" + title + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}


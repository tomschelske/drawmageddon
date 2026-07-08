package com.example.drawmageddon.model;

/** One player's finished canvas, submitted as a PNG data URL. */
public record Drawing(String id, String ownerId, String imageData) {}

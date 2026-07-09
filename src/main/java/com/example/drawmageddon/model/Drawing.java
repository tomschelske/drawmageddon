package com.example.drawmageddon.model;

/**
 * One player's finished canvas, submitted as a PNG data URL. The artist's
 * display name is captured at submission time so it survives a disconnect.
 */
public record Drawing(String id, String ownerId, String ownerName, String imageData) {}

package com.example.drawmageddon.model;

/** A prompt submitted by one player. Authorship is never revealed to clients. */
public record Prompt(String id, String ownerId, String text) {}

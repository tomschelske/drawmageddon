package com.example.drawmageddon.model;

import java.util.List;

/**
 * One round of the bracket. When an odd number of drawings enters the round,
 * one (randomly chosen) advances automatically as the bye.
 */
public record BracketRound(List<BracketMatch> matches, Drawing bye) {}

package io.autocrypt.jwlee.cowork.core.hitl;

import com.embabel.agent.api.annotation.State;

/**
 * Base state interface for vertical slice agents.
 * 
 * In Embabel, annotating the base interface with @State automatically
 * makes all implementing classes state records, enabling state scoping and looping.
 */
@State
public interface BaseAgentState {}

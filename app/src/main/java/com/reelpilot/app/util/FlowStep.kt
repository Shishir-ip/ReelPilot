package com.reelpilot.app.util

/**
 * One step of a recorded flow.
 * @param isText true = tap this position AND type the video's caption into
 *               the focused text field; false = just tap the position.
 * Coordinates are normalized 0..1 against screen size.
 */
data class FlowStep(val isText: Boolean, val x: Float, val y: Float)

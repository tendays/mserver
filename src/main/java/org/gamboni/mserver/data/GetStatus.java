package org.gamboni.mserver.data;

import org.gamboni.tech.web.js.JS;

import java.io.File;

/** WebSocket payload requesting all state updates
 * for the given directory, starting from the given
 * stamp number.
 *
 * @param directory
 * @param stamp
 */
@JS
public record GetStatus(File directory, int stamp) {
}

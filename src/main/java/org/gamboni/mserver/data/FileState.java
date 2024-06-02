package org.gamboni.mserver.data;

import org.gamboni.tech.web.js.JS;

import java.io.File;

@JS
public record FileState(File file, PlayState state) implements MServerEvent {}

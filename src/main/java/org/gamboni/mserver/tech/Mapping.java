package org.gamboni.mserver.tech;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.function.Supplier;

/** Helper object for mapping data to and from JSON, with special handling of Files which are represented by their
 * path relative to a root folder, as a string.
 */
public class Mapping implements Supplier<ObjectMapper> {
    private final ObjectMapper jacksonMapper;
    private final FileNameJsonFormat fileNameFormat;

    public Mapping(File rootFolder) {
        this.fileNameFormat = new FileNameJsonFormat(rootFolder);
        this.jacksonMapper = new ObjectMapper().registerModule(fileNameFormat.jacksonModule());
    }

    public File pathToFile(String path) {
        return fileNameFormat.pathToFile(path);
    }

    public String fileToPath(File file) {
        return fileNameFormat.fileToPath(file);
    }

    public String writeValueAsString(Object payload) {
        try {
            return jacksonMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T readValue(String text, Class<T> type) {
        try {
            return jacksonMapper.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ObjectMapper get() {
        return this.jacksonMapper;
    }
}

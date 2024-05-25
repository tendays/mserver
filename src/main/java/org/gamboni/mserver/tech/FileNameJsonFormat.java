package org.gamboni.mserver.tech;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.File;
import java.io.IOException;

/** Represents {@link File} instances as string holding a relative path. */
public class FileNameJsonFormat {
    private final File root;
    public FileNameJsonFormat(File root) {
        this.root = root.getAbsoluteFile();
    }

    public SimpleModule jacksonModule() {
        return new SimpleModule()
                .addSerializer(new StdSerializer<>(File.class) {
                    @Override
                    public void serialize(File value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                        gen.writeString(fileToPath(value));
                    }
                })
                .addDeserializer(File.class, new StdDeserializer<File>(File.class) {
                    @Override
                    public File deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
                        return pathToFile(p.getValueAsString());
                    }
                });
    }

    public String fileToPath(File value) {
        return checkIsInRoot(value).getPath().substring(root.getPath().length());
    }

    public File pathToFile(String path) {
        return checkIsInRoot(new File(root, path).getAbsoluteFile());
    }

    private File checkIsInRoot(File value) {
        value = value.getAbsoluteFile();
        String filePath = value.getPath();
        if (!filePath.startsWith(root.getPath())) {
            throw new IllegalArgumentException();
        }
        return value;
    }
}

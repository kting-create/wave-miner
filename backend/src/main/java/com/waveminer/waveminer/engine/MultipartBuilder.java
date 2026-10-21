package com.waveminer.waveminer.engine;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MultipartBuilder {

    private final String boundary = "waveminer-" + UUID.randomUUID();
    private final Map<String, String> fields = new LinkedHashMap<>();
    private String fileFieldName;
    private String fileName;
    private String fileContentType;
    private byte[] fileData;

    public MultipartBuilder add(String name, String value) {
        fields.put(name, value);
        return this;
    }

    public MultipartBuilder addFile(String fileFieldName, String filename, String contentType, byte[] data) {
        this.fileFieldName = fileFieldName;
        this.fileName = filename;
        this.fileContentType = contentType;
        this.fileData = data;
        return this;
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public byte[] build() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            if (fileFieldName != null) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + fileFieldName
                        + "\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: " + fileContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(fileData);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new EngineException("构造上传请求失败: " + e.getMessage(), e, null);
        }
    }
}

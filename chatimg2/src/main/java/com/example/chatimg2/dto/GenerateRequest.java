package com.example.chatimg2.dto;

import lombok.Data;
import java.util.List;

@Data
public class GenerateRequest {
    private String keyCode;
    private String prompt;
    /** text2img | img2img */
    private String type;
    /** base64 encoded source image for img2img */
    private String sourceImage;
    private List<String> sourceImages;
    /** frontend assistant message id used to bind async generation result */
    private String clientMessageId;
}

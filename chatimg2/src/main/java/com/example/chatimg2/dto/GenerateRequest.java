package com.example.chatimg2.dto;

import lombok.Data;

@Data
public class GenerateRequest {
    private String keyCode;
    private String prompt;
    /** text2img | img2img */
    private String type;
    /** base64 encoded source image for img2img */
    private String sourceImage;
    /** API model name, e.g. gpt-image-1 */
    private String model;
}

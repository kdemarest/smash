// AWS Lambda function for smash email forwarding via SES
// Supports text-only messages and messages with image attachments
// 
// Images are uploaded to S3 and included as <img> tags in the email HTML
//
// Deploy this to AWS Lambda and configure API Gateway to get a public URL
// 
// Environment variables:
//   FROM_EMAIL - The verified SES email to send from
//   S3_BUCKET  - (optional) S3 bucket for images, auto-detected if not set

import { SESClient, SendEmailCommand } from "@aws-sdk/client-ses";
import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";
import { STSClient, GetCallerIdentityCommand } from "@aws-sdk/client-sts";
import { randomUUID } from "crypto";

const ses = new SESClient();
const s3 = new S3Client();

// From email: use Lambda env var (must be SES-verified)
const FROM_EMAIL = process.env.FROM_EMAIL || "sms@example.com";

// S3 bucket: use env var or auto-detect based on account ID
const S3_BUCKET = process.env.S3_BUCKET || null;

// Cached account ID for bucket name detection
let cachedAccountId = null;

// Get bucket name (auto-detect if not configured)
async function getBucketName() {
    if (S3_BUCKET) return S3_BUCKET;
    
    // Auto-detect: smash-images-{accountId}
    if (!cachedAccountId) {
        const sts = new STSClient();
        const identity = await sts.send(new GetCallerIdentityCommand());
        cachedAccountId = identity.Account;
    }
    
    return `smash-images-${cachedAccountId}`;
}

// Upload a base64 image to S3 and return the public URL
async function uploadImageToS3(base64Data, mimeType, bucketName) {
    // Generate a unique path: YYYY/MM/DD/uuid.ext
    const now = new Date();
    const datePath = `${now.getFullYear()}/${String(now.getMonth() + 1).padStart(2, '0')}/${String(now.getDate()).padStart(2, '0')}`;
    
    // Determine file extension from MIME type
    const extMap = {
        'image/jpeg': 'jpg',
        'image/jpg': 'jpg',
        'image/png': 'png',
        'image/gif': 'gif',
        'image/webp': 'webp',
        'image/heic': 'heic',
        'image/heif': 'heif'
    };
    const ext = extMap[mimeType.toLowerCase()] || 'jpg';
    
    const key = `${datePath}/${randomUUID()}.${ext}`;
    
    // Decode base64 to buffer
    const buffer = Buffer.from(base64Data, 'base64');
    
    // Upload to S3
    const command = new PutObjectCommand({
        Bucket: bucketName,
        Key: key,
        Body: buffer,
        ContentType: mimeType,
        CacheControl: 'max-age=31536000' // Cache for 1 year (immutable content)
    });
    
    await s3.send(command);
    
    // Return public URL
    const region = process.env.AWS_REGION || 'us-east-1';
    if (region === 'us-east-1') {
        return `https://${bucketName}.s3.amazonaws.com/${key}`;
    } else {
        return `https://${bucketName}.s3.${region}.amazonaws.com/${key}`;
    }
}

// Build HTML email body with images
function buildEmailHtml(origin, messageBody, dateStr, imageUrls) {
    const escapedBody = messageBody
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/\n/g, '<br>');
    
    let html = `<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 20px; }
        .header { color: #666; font-size: 12px; margin-bottom: 10px; }
        .message { font-size: 16px; margin-bottom: 20px; white-space: pre-wrap; }
        .images { margin-top: 20px; }
        .images img { max-width: 100%; max-height: 600px; margin: 10px 0; display: block; border-radius: 8px; }
        .footer { color: #999; font-size: 11px; margin-top: 20px; border-top: 1px solid #eee; padding-top: 10px; }
    </style>
</head>
<body>
    <div class="header">From: ${origin}</div>
    <div class="message">${escapedBody}</div>`;
    
    if (imageUrls && imageUrls.length > 0) {
        html += `    <div class="images">\n`;
        for (const url of imageUrls) {
            html += `        <img src="${url}" alt="Attached image" />\n`;
        }
        html += `    </div>\n`;
    }
    
    html += `    <div class="footer">Received: ${dateStr}</div>
</body>
</html>`;
    
    return html;
}

// Build plain text email body
function buildEmailText(origin, messageBody, dateStr, imageUrls) {
    let text = `From: ${origin}\n\n${messageBody}\n`;
    
    if (imageUrls && imageUrls.length > 0) {
        text += `\nAttached images:\n`;
        for (const url of imageUrls) {
            text += `${url}\n`;
        }
    }
    
    text += `\nReceived: ${dateStr}`;
    return text;
}

export const handler = async (event) => {
    try {
        // Validate FROM_EMAIL is configured
        if (!process.env.FROM_EMAIL || FROM_EMAIL === "sms@example.com") {
            return {
                statusCode: 500,
                body: JSON.stringify({ 
                    error: "FROM_EMAIL environment variable not configured",
                    hint: "Set FROM_EMAIL to a verified SES email address"
                })
            };
        }

        // Parse the incoming request body
        let body;
        try {
            body = typeof event.body === 'string' ? JSON.parse(event.body) : event.body;
        } catch (parseErr) {
            return {
                statusCode: 400,
                body: JSON.stringify({ error: "Invalid JSON in request body" })
            };
        }
        const { origin, destination_email, body: messageBody, timestamp, images } = body;

        // Log the request for debugging
        const imageCount = images?.length || 0;
        console.log(`Request: from=${origin}, to=${destination_email}, images=${imageCount}, FROM_EMAIL=${FROM_EMAIL}`);

        // Validate required fields
        if (!origin || !destination_email || !messageBody) {
            return {
                statusCode: 400,
                body: JSON.stringify({ error: "Missing required fields: origin, destination_email, body" })
            };
        }

        // Format the timestamp
        const dateStr = timestamp 
            ? new Date(timestamp).toISOString() 
            : new Date().toISOString();

        // Process images if present
        let imageUrls = [];
        let imageErrors = [];
        if (images && images.length > 0) {
            const bucketName = await getBucketName();
            console.log(`Uploading ${images.length} images to S3 bucket: ${bucketName}`);
            
            for (const img of images) {
                try {
                    const url = await uploadImageToS3(img.data, img.mimeType || 'image/jpeg', bucketName);
                    imageUrls.push(url);
                    console.log(`  Uploaded: ${url}`);
                } catch (uploadErr) {
                    console.error(`  Failed to upload image: ${uploadErr.message}`);
                    imageErrors.push(uploadErr.message);
                }
            }
            
            // If ALL images failed to upload, return an error
            if (imageUrls.length === 0 && imageErrors.length > 0) {
                return {
                    statusCode: 500,
                    body: JSON.stringify({ 
                        error: "All image uploads failed",
                        details: imageErrors
                    })
                };
            }
            
            // Warn if some (but not all) images failed
            if (imageErrors.length > 0) {
                console.warn(`Partial image upload: ${imageUrls.length} succeeded, ${imageErrors.length} failed`);
            }
        }

        // Build email content
        const hasImages = imageUrls.length > 0;
        const subject = hasImages 
            ? `SMS from ${origin} (${imageUrls.length} image${imageUrls.length > 1 ? 's' : ''})`
            : `SMS from ${origin}`;

        // Send email via SES
        const emailParams = {
            Source: FROM_EMAIL,
            Destination: {
                ToAddresses: [destination_email]
            },
            Message: {
                Subject: {
                    Data: subject,
                    Charset: "UTF-8"
                },
                Body: {
                    Text: {
                        Data: buildEmailText(origin, messageBody, dateStr, imageUrls),
                        Charset: "UTF-8"
                    }
                }
            }
        };

        // Add HTML body if we have images (for inline display)
        if (hasImages) {
            emailParams.Message.Body.Html = {
                Data: buildEmailHtml(origin, messageBody, dateStr, imageUrls),
                Charset: "UTF-8"
            };
        }

        const command = new SendEmailCommand(emailParams);
        const result = await ses.send(command);
        console.log(`SES MessageId: ${result.MessageId}`);

        const response = { 
            status: "sent", 
            to: destination_email, 
            messageId: result.MessageId,
            imagesUploaded: imageUrls.length
        };
        
        // Include warning if some images failed
        if (imageErrors.length > 0) {
            response.imagesFailed = imageErrors.length;
            response.warning = `${imageErrors.length} image(s) failed to upload`;
        }

        return {
            statusCode: 200,
            body: JSON.stringify(response)
        };

    } catch (error) {
        console.error("Error:", error);
        
        // Provide helpful hints for common errors
        let hint = null;
        const errName = error.name || error.code || '';
        const errMsg = error.message || '';
        
        if (errName === 'MessageRejected' || errMsg.includes('not verified')) {
            hint = `Ensure FROM_EMAIL (${FROM_EMAIL}) is verified in SES`;
        } else if (errName === 'AccessDenied' || errMsg.includes('not authorized')) {
            hint = "Lambda role missing required permissions (SES, S3, or STS)";
        } else if (errName === 'Throttling' || errMsg.includes('rate exceeded')) {
            hint = "SES sending rate exceeded - try again later";
        } else if (errMsg.includes('getaddrinfo') || errMsg.includes('ENOTFOUND')) {
            hint = "Network error - cannot reach AWS services";
        }
        
        return {
            statusCode: 500,
            body: JSON.stringify({ 
                error: errMsg,
                code: errName,
                from: FROM_EMAIL,
                ...(hint && { hint })
            })
        };
    }
};

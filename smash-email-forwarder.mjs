// AWS Lambda function for smash email forwarding via SES
// Deploy this to AWS Lambda and configure API Gateway to get a public URL
// 
// Set the FROM_EMAIL environment variable in Lambda configuration
// (or via deploy-lambdas.js with SMASH_FROM_EMAIL env var)

import { SESClient, SendEmailCommand } from "@aws-sdk/client-ses";

const ses = new SESClient();

// From email: use Lambda env var, or fall back to a default (must be SES-verified)
const FROM_EMAIL = process.env.FROM_EMAIL || "sms@example.com";

export const handler = async (event) => {
    try {
        // Parse the incoming request body
        const body = typeof event.body === 'string' ? JSON.parse(event.body) : event.body;
        const { origin, destination_email, body: messageBody, timestamp } = body;

        // Log the request for debugging
        console.log(`Request: from=${origin}, to=${destination_email}, FROM_EMAIL=${FROM_EMAIL}`);

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

        // Send email via SES
        const command = new SendEmailCommand({
            Source: FROM_EMAIL,
            Destination: {
                ToAddresses: [destination_email]
            },
            Message: {
                Subject: {
                    Data: `SMS from ${origin}`,
                    Charset: "UTF-8"
                },
                Body: {
                    Text: {
                        Data: `${messageBody}\n\nReceived: ${dateStr}`,
                        Charset: "UTF-8"
                    }
                }
            }
        });

        const result = await ses.send(command);
        console.log(`SES MessageId: ${result.MessageId}`);

        return {
            statusCode: 200,
            body: JSON.stringify({ status: "sent", to: destination_email, messageId: result.MessageId })
        };

    } catch (error) {
        console.error("Error sending email:", error);
        // Return detailed error info
        return {
            statusCode: 500,
            body: JSON.stringify({ 
                error: error.message,
                code: error.name || error.code,
                from: FROM_EMAIL
            })
        };
    }
};

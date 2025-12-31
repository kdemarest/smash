// AWS Lambda function for smash log ingestion via CloudWatch
// Deploy this to AWS Lambda and configure API Gateway to get a public URL

import { CloudWatchLogsClient, PutLogEventsCommand, CreateLogStreamCommand, DescribeLogStreamsCommand } from "@aws-sdk/client-cloudwatch-logs";

const cwl = new CloudWatchLogsClient();

// CloudWatch Log Group - create this in AWS Console or via CLI first
const LOG_GROUP_NAME = "/smash/app-logs";

export const handler = async (event) => {
    try {
        // Parse the incoming request body
        const body = typeof event.body === 'string' ? JSON.parse(event.body) : event.body;
        
        // Expected payload:
        // { "device": "moto-g5", "lines": ["2024-12-31 10:00:00 [info] message", ...] }
        // Or single line:
        // { "device": "moto-g5", "line": "2024-12-31 10:00:00 [info] message" }
        
        const device = body.device || "unknown";
        const lines = body.lines || (body.line ? [body.line] : []);
        
        if (lines.length === 0) {
            return {
                statusCode: 400,
                body: JSON.stringify({ error: "No log lines provided" })
            };
        }

        // Use device name as log stream (one stream per device)
        const logStreamName = device;
        
        // Ensure log stream exists
        await ensureLogStream(logStreamName);
        
        // Get sequence token for the stream
        const sequenceToken = await getSequenceToken(logStreamName);
        
        // Prepare log events
        const logEvents = lines.map(line => ({
            timestamp: extractTimestamp(line) || Date.now(),
            message: line
        }));
        
        // Sort by timestamp (required by CloudWatch)
        logEvents.sort((a, b) => a.timestamp - b.timestamp);
        
        // Put log events
        const putCommand = new PutLogEventsCommand({
            logGroupName: LOG_GROUP_NAME,
            logStreamName: logStreamName,
            logEvents: logEvents,
            sequenceToken: sequenceToken
        });
        
        await cwl.send(putCommand);
        
        console.log(`Logged ${lines.length} lines for device ${device}`);
        
        return {
            statusCode: 200,
            body: JSON.stringify({ status: "logged", count: lines.length })
        };

    } catch (error) {
        console.error("Error logging:", error);
        return {
            statusCode: 500,
            body: JSON.stringify({ error: error.message })
        };
    }
};

/**
 * Ensure the log stream exists, create if not.
 */
async function ensureLogStream(logStreamName) {
    try {
        const createCommand = new CreateLogStreamCommand({
            logGroupName: LOG_GROUP_NAME,
            logStreamName: logStreamName
        });
        await cwl.send(createCommand);
    } catch (error) {
        // ResourceAlreadyExistsException is fine
        if (error.name !== 'ResourceAlreadyExistsException') {
            throw error;
        }
    }
}

/**
 * Get the sequence token for a log stream (needed for PutLogEvents).
 */
async function getSequenceToken(logStreamName) {
    const describeCommand = new DescribeLogStreamsCommand({
        logGroupName: LOG_GROUP_NAME,
        logStreamNamePrefix: logStreamName,
        limit: 1
    });
    
    const response = await cwl.send(describeCommand);
    const stream = response.logStreams?.find(s => s.logStreamName === logStreamName);
    return stream?.uploadSequenceToken;
}

/**
 * Try to extract timestamp from log line format: "2024-12-31-10-30-45 [info] message"
 */
function extractTimestamp(line) {
    // Match: YYYY-MM-DD-HH-MM-SS or YYYY-MM-DD HH:MM:SS
    const match = line.match(/^(\d{4})-(\d{2})-(\d{2})[-\s](\d{2})[-:](\d{2})[-:](\d{2})/);
    if (match) {
        const [, year, month, day, hour, min, sec] = match;
        return new Date(year, month - 1, day, hour, min, sec).getTime();
    }
    return null;
}

// AWS Lambda function for viewing smash logs from CloudWatch
// Deploy this to AWS Lambda and configure API Gateway to get a public URL
// 
// GET - Read logs: ?device=name&limit=100
// GET - List devices: (no params)

import { CloudWatchLogsClient, DescribeLogStreamsCommand, GetLogEventsCommand } from "@aws-sdk/client-cloudwatch-logs";

const cwl = new CloudWatchLogsClient();

// CloudWatch Log Group - must match smash-log-receiver
const LOG_GROUP_NAME = "/smash/app-logs";

export const handler = async (event) => {
    // Handle CORS preflight
    const method = event.requestContext?.http?.method || event.httpMethod;
    if (method === 'OPTIONS') {
        return { statusCode: 200, headers: corsHeaders(), body: '' };
    }
    
    // Only handle GET requests
    if (method !== 'GET') {
        return {
            statusCode: 405,
            headers: corsHeaders(),
            body: JSON.stringify({ error: "Method not allowed. Use GET." })
        };
    }
    
    try {
        const params = event.queryStringParameters || {};
        const device = params.device;
        const limit = Math.min(parseInt(params.limit) || 100, 500);
        
        // If no device specified, list available devices (log streams)
        if (!device) {
            const streams = await listLogStreams();
            return {
                statusCode: 200,
                headers: corsHeaders(),
                body: JSON.stringify({ devices: streams })
            };
        }
        
        // Fetch logs for specific device
        const logs = await fetchLogs(device, limit);
        return {
            statusCode: 200,
            headers: corsHeaders(),
            body: JSON.stringify({ device, logs, count: logs.length })
        };
        
    } catch (error) {
        console.error("Error fetching logs:", error);
        return {
            statusCode: 500,
            headers: corsHeaders(),
            body: JSON.stringify({ error: error.message })
        };
    }
};

/**
 * List all log streams (devices) in the log group
 */
async function listLogStreams() {
    try {
        const command = new DescribeLogStreamsCommand({
            logGroupName: LOG_GROUP_NAME,
            orderBy: 'LastEventTime',
            descending: true,
            limit: 50
        });
        const response = await cwl.send(command);
        return (response.logStreams || []).map(s => ({
            name: s.logStreamName,
            lastEvent: s.lastEventTimestamp ? new Date(s.lastEventTimestamp).toISOString() : null
        }));
    } catch (error) {
        if (error.name === 'ResourceNotFoundException') {
            return [];
        }
        throw error;
    }
}

/**
 * Fetch logs for a specific device (log stream)
 */
async function fetchLogs(device, limit) {
    try {
        const command = new GetLogEventsCommand({
            logGroupName: LOG_GROUP_NAME,
            logStreamName: device,
            limit: limit,
            startFromHead: false  // Get most recent logs
        });
        const response = await cwl.send(command);
        return (response.events || []).map(e => e.message);
    } catch (error) {
        if (error.name === 'ResourceNotFoundException') {
            return [];
        }
        throw error;
    }
}

/**
 * CORS headers for browser access
 */
function corsHeaders() {
    return {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type',
        'Content-Type': 'application/json'
    };
}

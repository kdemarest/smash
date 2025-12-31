// Deploy Lambda functions to AWS (full setup including IAM and API Gateway)
// Usage: node deploy-lambdas.js [-quick]
//
// Options:
//   -quick  Skip IAM/API Gateway setup, just update Lambda code
//
// Environment variables (optional):
//   SMASH_FROM_EMAIL - The verified SES email to send from

const { execSync } = require('child_process');

const QUICK_MODE = process.argv.includes('-quick') || process.argv.includes('--quick');
const fs = require('fs');

// Smoke test all Lambda source files before deploying any
function smokeTestAll() {
    console.log('Smoke testing all Lambda files...');
    const errors = [];
    
    for (const lambda of LAMBDAS) {
        process.stdout.write(`  ${lambda.sourceFile}... `);
        try {
            execSync(`node --check ${lambda.sourceFile}`, { encoding: 'utf8', stdio: 'pipe' });
            console.log('OK');
        } catch (err) {
            console.log('FAILED');
            errors.push({ file: lambda.sourceFile, error: err.stderr || err.message });
        }
    }
    
    if (errors.length > 0) {
        console.error('\n❌ Syntax errors found - aborting deploy:\n');
        for (const { file, error } of errors) {
            console.error(`--- ${file} ---`);
            console.error(error);
        }
        process.exit(1);
    }
    
    console.log('✓ All Lambda files passed syntax check\n');
}

const LAMBDAS = [
    {
        name: 'smash-email-forwarder',
        sourceFile: 'smash-email-forwarder.mjs',
        description: 'Email forwarding via SES (with S3 image upload)',
        policies: [
            'arn:aws:iam::aws:policy/AmazonSESFullAccess',
            'arn:aws:iam::aws:policy/AmazonS3FullAccess'  // For uploading images
        ],
        envVars: {
            FROM_EMAIL: process.env.SMASH_FROM_EMAIL || ''
        }
    },
    {
        name: 'smash-log-receiver',
        sourceFile: 'smash-log-receiver.mjs',
        description: 'Log ingestion via CloudWatch',
        policies: ['arn:aws:iam::aws:policy/CloudWatchLogsFullAccess'],
        envVars: {}
    }
];

const ZIP_FILE = 'lambda-deploy.zip';
const REGION = getRegion();

function getRegion() {
    try {
        const result = execSync('aws configure get region', { encoding: 'utf8' }).trim();
        return result || 'us-east-1';
    } catch {
        return 'us-east-1';
    }
}

function run(cmd, options = {}) {
    if (!options.silent) console.log(`> ${cmd}`);
    try {
        return execSync(cmd, { encoding: 'utf8', stdio: options.silent ? 'pipe' : 'inherit', ...options });
    } catch (err) {
        if (options.ignoreError) return null;
        throw err;
    }
}

function runJson(cmd) {
    try {
        const result = execSync(cmd, { encoding: 'utf8', stdio: 'pipe' });
        return JSON.parse(result);
    } catch {
        return null;
    }
}

function getAccountId() {
    const identity = runJson('aws sts get-caller-identity');
    return identity?.Account;
}

// Trust policy for Lambda
const TRUST_POLICY = {
    Version: "2012-10-17",
    Statement: [{
        Effect: "Allow",
        Principal: { Service: "lambda.amazonaws.com" },
        Action: "sts:AssumeRole"
    }]
};

async function ensureRole(lambda) {
    const roleName = `${lambda.name}-role`;
    
    // Check if role exists
    const existingRole = runJson(`aws iam get-role --role-name ${roleName} 2>nul`);
    
    if (!existingRole) {
        console.log(`  Creating IAM role: ${roleName}`);
        
        // Write trust policy to temp file
        fs.writeFileSync('trust-policy.json', JSON.stringify(TRUST_POLICY));
        
        run(`aws iam create-role --role-name ${roleName} --assume-role-policy-document file://trust-policy.json`, { silent: true });
        
        fs.unlinkSync('trust-policy.json');
        
        // Attach basic Lambda execution policy
        run(`aws iam attach-role-policy --role-name ${roleName} --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole`, { silent: true });
        
        // Attach additional policies
        for (const policy of lambda.policies || []) {
            console.log(`  Attaching policy: ${policy.split('/').pop()}`);
            run(`aws iam attach-role-policy --role-name ${roleName} --policy-arn ${policy}`, { silent: true });
        }
        
        // Wait for role to propagate
        console.log('  Waiting for IAM role to propagate...');
        await sleep(10000);
    } else {
        console.log(`  IAM role exists: ${roleName}`);
    }
    
    return `arn:aws:iam::${getAccountId()}:role/${roleName}`;
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function ensureLambda(lambda, roleArn) {
    // Check if Lambda exists
    const existingLambda = runJson(`aws lambda get-function --function-name ${lambda.name} 2>nul`);
    
    // Create zip
    if (fs.existsSync(ZIP_FILE)) fs.unlinkSync(ZIP_FILE);
    fs.copyFileSync(lambda.sourceFile, 'index.mjs');
    run(`powershell -Command "Compress-Archive -Path index.mjs -DestinationPath ${ZIP_FILE}"`, { silent: true });
    fs.unlinkSync('index.mjs');
    
    if (!existingLambda) {
        console.log(`  Creating Lambda function: ${lambda.name}`);
        run(`aws lambda create-function --function-name ${lambda.name} --runtime nodejs20.x --role ${roleArn} --handler index.handler --zip-file fileb://${ZIP_FILE} --architectures arm64`, { silent: true });
    } else {
        console.log(`  Updating Lambda function: ${lambda.name}`);
        run(`aws lambda update-function-code --function-name ${lambda.name} --zip-file fileb://${ZIP_FILE}`, { silent: true });
    }
    
    // Set environment variables if any
    const envVars = lambda.envVars || {};
    const filteredEnvVars = Object.fromEntries(
        Object.entries(envVars).filter(([k, v]) => v)  // Only include non-empty values
    );
    
    if (Object.keys(filteredEnvVars).length > 0) {
        console.log(`  Setting environment variables: ${Object.keys(filteredEnvVars).join(', ')}`);
        const envJson = JSON.stringify({ Variables: filteredEnvVars });
        fs.writeFileSync('lambda-env.json', envJson);
        run(`aws lambda update-function-configuration --function-name ${lambda.name} --environment file://lambda-env.json`, { silent: true });
        fs.unlinkSync('lambda-env.json');
    }
    
    if (fs.existsSync(ZIP_FILE)) fs.unlinkSync(ZIP_FILE);
}

async function ensureApiGateway(lambda) {
    const apiName = `${lambda.name}-api`;
    
    // Check if API exists
    const apis = runJson('aws apigatewayv2 get-apis');
    let api = apis?.Items?.find(a => a.Name === apiName);
    
    if (!api) {
        console.log(`  Creating API Gateway: ${apiName}`);
        
        // Create HTTP API
        const createResult = runJson(`aws apigatewayv2 create-api --name ${apiName} --protocol-type HTTP --target arn:aws:lambda:${REGION}:${getAccountId()}:function:${lambda.name}`);
        api = createResult;
        
        // Add Lambda permission for API Gateway to invoke
        const sourceArn = `arn:aws:execute-api:${REGION}:${getAccountId()}:${api.ApiId}/*/*`;
        run(`aws lambda add-permission --function-name ${lambda.name} --statement-id apigateway-invoke-${Date.now()} --action lambda:InvokeFunction --principal apigateway.amazonaws.com --source-arn "${sourceArn}"`, { silent: true, ignoreError: true });
    } else {
        console.log(`  API Gateway exists: ${apiName}`);
    }
    
    return `${api.ApiEndpoint}/${lambda.name}`;
}

async function deployLambda(lambda) {
    console.log(`\n${'='.repeat(50)}`);
    console.log(`Deploying: ${lambda.name}${QUICK_MODE ? ' (quick)' : ''}`);
    console.log(`${lambda.description}`);
    console.log('='.repeat(50));

    // Check source file exists
    if (!fs.existsSync(lambda.sourceFile)) {
        console.error(`ERROR: ${lambda.sourceFile} not found, skipping`);
        return null;
    }

    if (QUICK_MODE) {
        // Quick mode: just update the code
        await updateLambdaCode(lambda);
        console.log(`✓ Code updated`);
        return null;  // Don't return endpoint in quick mode
    }

    const roleArn = await ensureRole(lambda);
    await ensureLambda(lambda, roleArn);
    const endpoint = await ensureApiGateway(lambda);
    
    console.log(`✓ Deployed successfully`);
    console.log(`  Endpoint: ${endpoint}`);
    
    return endpoint;
}

async function updateLambdaCode(lambda) {
    // Create zip
    if (fs.existsSync(ZIP_FILE)) fs.unlinkSync(ZIP_FILE);
    fs.copyFileSync(lambda.sourceFile, 'index.mjs');
    run(`powershell -Command "Compress-Archive -Path index.mjs -DestinationPath ${ZIP_FILE}"`, { silent: true });
    fs.unlinkSync('index.mjs');

    console.log(`  Updating Lambda code: ${lambda.name}`);
    run(`aws lambda update-function-code --function-name ${lambda.name} --zip-file fileb://${ZIP_FILE}`, { silent: true });

    if (fs.existsSync(ZIP_FILE)) fs.unlinkSync(ZIP_FILE);
}

async function main() {
    // Smoke test all files first
    smokeTestAll();
    
    // Check AWS CLI is configured
    console.log('=== Checking AWS CLI ===');
    try {
        run('aws sts get-caller-identity', { silent: true });
        console.log(`AWS CLI configured OK (region: ${REGION})`);
    } catch {
        console.error('ERROR: AWS CLI not configured. Run: aws configure');
        process.exit(1);
    }

    if (QUICK_MODE) {
        console.log('Quick mode: updating Lambda code only');
    }

    const endpoints = {};
    
    for (const lambda of LAMBDAS) {
        const endpoint = await deployLambda(lambda);
        if (endpoint) {
            endpoints[lambda.name] = endpoint;
        }
    }

    console.log('\n=== Summary ===');
    if (QUICK_MODE) {
        console.log('Lambda code updated.');
    } else {
        for (const [name, endpoint] of Object.entries(endpoints)) {
            console.log(`${name}: ${endpoint}`);
        }
        
        if (endpoints['smash-email-forwarder']) {
            console.log(`\nTo configure smash, send SMS:`);
            console.log(`Cmd setmail ${endpoints['smash-email-forwarder']}`);
        }
    }
    
    console.log('\n=== Done ===\n');
}

main().catch(err => {
    console.error('Error:', err.message);
    process.exit(1);
});

console.log('\n=== Done ===');

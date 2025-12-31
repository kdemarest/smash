// Provision S3 bucket for smash image storage
// Usage: node provision-s3.js
//
// Creates an S3 bucket with public read access for storing MMS images
// that will be included in forwarded emails.

const { execSync } = require('child_process');
const fs = require('fs');

const BUCKET_NAME = 'smash-images-' + getAccountId();
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

function bucketExists(bucketName) {
    try {
        execSync(`aws s3api head-bucket --bucket ${bucketName}`, { stdio: 'pipe' });
        return true;
    } catch {
        return false;
    }
}

// Bucket policy for public read access to objects
function getBucketPolicy(bucketName) {
    return {
        Version: "2012-10-17",
        Statement: [
            {
                Sid: "PublicReadGetObject",
                Effect: "Allow",
                Principal: "*",
                Action: "s3:GetObject",
                Resource: `arn:aws:s3:::${bucketName}/*`
            }
        ]
    };
}

// CORS configuration for browser access
const CORS_CONFIG = {
    CORSRules: [
        {
            AllowedHeaders: ["*"],
            AllowedMethods: ["GET"],
            AllowedOrigins: ["*"],
            MaxAgeSeconds: 3600
        }
    ]
};

async function main() {
    console.log('\n=== Provisioning S3 Bucket for smash ===\n');

    // Check AWS CLI is configured
    try {
        run('aws sts get-caller-identity', { silent: true });
        console.log(`AWS CLI configured OK (region: ${REGION})`);
    } catch {
        console.error('ERROR: AWS CLI not configured. Run: aws configure');
        process.exit(1);
    }

    const accountId = getAccountId();
    const bucketName = `smash-images-${accountId}`;
    
    console.log(`\nBucket name: ${bucketName}`);

    // Check if bucket exists
    if (bucketExists(bucketName)) {
        console.log('Bucket already exists, checking configuration...');
    } else {
        console.log('Creating bucket...');
        
        // Create bucket (us-east-1 doesn't use LocationConstraint)
        if (REGION === 'us-east-1') {
            run(`aws s3api create-bucket --bucket ${bucketName}`, { silent: true });
        } else {
            run(`aws s3api create-bucket --bucket ${bucketName} --create-bucket-configuration LocationConstraint=${REGION}`, { silent: true });
        }
        
        console.log('  ✓ Bucket created');
    }

    // Disable block public access (required for public bucket policy)
    console.log('Configuring public access settings...');
    run(`aws s3api put-public-access-block --bucket ${bucketName} --public-access-block-configuration BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false`, { silent: true });
    console.log('  ✓ Public access enabled');

    // Apply bucket policy for public read
    console.log('Applying bucket policy...');
    const policy = getBucketPolicy(bucketName);
    fs.writeFileSync('bucket-policy.json', JSON.stringify(policy, null, 2));
    run(`aws s3api put-bucket-policy --bucket ${bucketName} --policy file://bucket-policy.json`, { silent: true });
    fs.unlinkSync('bucket-policy.json');
    console.log('  ✓ Bucket policy applied (public read)');

    // Apply CORS configuration
    console.log('Applying CORS configuration...');
    fs.writeFileSync('cors-config.json', JSON.stringify(CORS_CONFIG, null, 2));
    run(`aws s3api put-bucket-cors --bucket ${bucketName} --cors-configuration file://cors-config.json`, { silent: true });
    fs.unlinkSync('cors-config.json');
    console.log('  ✓ CORS configured');

    // Set lifecycle rule to delete old images (optional, 90 days)
    console.log('Applying lifecycle policy (90-day expiration)...');
    const lifecyclePolicy = {
        Rules: [
            {
                ID: "DeleteOldImages",
                Status: "Enabled",
                Filter: { Prefix: "" },
                Expiration: { Days: 90 }
            }
        ]
    };
    fs.writeFileSync('lifecycle-policy.json', JSON.stringify(lifecyclePolicy, null, 2));
    run(`aws s3api put-bucket-lifecycle-configuration --bucket ${bucketName} --lifecycle-configuration file://lifecycle-policy.json`, { silent: true });
    fs.unlinkSync('lifecycle-policy.json');
    console.log('  ✓ Lifecycle policy applied');

    // Output summary
    const bucketUrl = REGION === 'us-east-1' 
        ? `https://${bucketName}.s3.amazonaws.com`
        : `https://${bucketName}.s3.${REGION}.amazonaws.com`;

    console.log('\n=== S3 Bucket Provisioned ===');
    console.log(`Bucket Name: ${bucketName}`);
    console.log(`Bucket URL:  ${bucketUrl}`);
    console.log(`Region:      ${REGION}`);
    console.log('\nImages uploaded to this bucket will be publicly accessible.');
    console.log('Objects expire after 90 days.');
    
    console.log('\n=== Next Steps ===');
    console.log('1. Run: node deploy-lambdas.js');
    console.log('   (The Lambda will auto-detect this bucket by naming convention)');
    console.log('\n=== Done ===\n');
}

main().catch(err => {
    console.error('Error:', err.message);
    process.exit(1);
});

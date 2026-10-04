# CraveDash

CraveDash is a Spring Boot REST API and React frontend for food-order management. Amazon DynamoDB is the persistent store for orders, status history, accounts, and login sessions. Redis/MemoryDB is not required.

## Requirements

- Java 21+
- Maven 3.9+
- Docker Desktop for local DynamoDB
- Node.js and npm for the frontend

## Run locally

Start DynamoDB Local from the repository root:

```powershell
docker compose up -d dynamodb-local
```

Configure the AWS SDK with local-only credentials. DynamoDB Local accepts any values; these are not AWS credentials:

```powershell
$env:AWS_ACCESS_KEY_ID = "local"
$env:AWS_SECRET_ACCESS_KEY = "local"
$env:AWS_REGION = "us-east-1"
$env:DYNAMODB_ENDPOINT = "http://localhost:8000"
$env:DYNAMODB_AUTO_CREATE_TABLES = "true"
$env:CRAVEDASH_AUTH_USERNAME = "admin"
$env:CRAVEDASH_AUTH_PASSWORD = "choose-a-strong-password"
$env:CRAVEDASH_FRONTEND_ORIGIN = "http://localhost:5173"
mvn spring-boot:run
```

Table creation is opt-in, defaults to disabled, and is rejected unless a DynamoDB endpoint is explicitly configured. The application never creates or deletes production AWS tables on startup.

In another PowerShell terminal, start the frontend:

```powershell
Set-Location frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Customer accounts can be created at `/register`; the owner signs in with the configured bootstrap credentials.

## AWS configuration

For AWS, omit `DYNAMODB_ENDPOINT` and set `AWS_REGION`. Configure the normal AWS SDK credential provider chain (prefer an EC2 instance profile/IAM role). The role needs read/write access to the configured table and its `gsi1` index. Set:

```text
DYNAMODB_TABLE_NAME=CraveDashData
DYNAMODB_AUTO_CREATE_TABLES=false
AWS_REGION=us-east-1
```

Provision the table before deploying the application. Do not set `DYNAMODB_AUTO_CREATE_TABLES=true` for AWS. Never put access keys, secret keys, or passwords in source control.

## DynamoDB table

The single `CraveDashData` table uses string partition key `pk` and string sort key `sk`. Its `gsi1` global secondary index has string partition key `gsi1pk`, string sort key `gsi1sk`, and `ALL` projection.

| Item | `pk` | `sk` | Purpose |
|---|---|---|---|
| Order | `ORDER#{orderId}` | `META` | Order JSON and status |
| Order history | `ORDER#{orderId}` | `HISTORY#{timestamp}#{sequence}` | Ordered status events |
| User account | `USER#{username}` | `META` | Role and BCrypt password hash |
| Session | `SESSION#{token}` | `META` | Username and application-enforced expiry |
| Order counter | `SYSTEM#ORDER_SEQUENCE` | `META` | Atomic ID allocation starting at 5001 |

Each order also has `gsi1pk=ORDERS` and a `gsi1sk` derived from its customer username and order ID. That index serves owner-wide and customer-specific order queries without table scans. History is queried by order partition and timestamp sort key. Order writes and their history events use DynamoDB transactions. Session expiry is checked on every lookup; AWS table TTL can optionally be enabled for the `expiresAt` attribute to clean up expired session records.

## API compatibility

Existing routes and response fields for orders and authentication remain unchanged. The public `GET /api/health` response now reports DynamoDB:

```json
{
  "status": "UP",
  "dynamoDB": "CONNECTED",
  "storageTarget": "LOCAL_DYNAMODB"
}
```

Authenticated endpoints:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`
- `POST /api/orders`
- `GET /api/orders`
- `GET /api/orders/{orderId}`
- `PUT /api/orders/{orderId}/status`
- `GET /api/orders/{orderId}/history`

Customers can access only their own orders; owners can see all orders and advance order status one step at a time. Customer passwords are stored as BCrypt hashes. Session cookies remain HttpOnly and expire after eight hours.

## Build and verify

From the repository root:

```powershell
mvn clean test
```

Check DynamoDB connectivity after startup:

```powershell
curl.exe http://localhost:8080/api/health
```

To stop local DynamoDB:

```powershell
docker compose down
```

Data stored in the old Redis/MemoryDB instance is not copied automatically. Export or migrate that data separately before switching an existing deployment; the application no longer reads from Redis.

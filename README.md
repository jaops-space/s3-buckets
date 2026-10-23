# Yamcs S3 Buckets

Yamcs bucket provider storing the bucket objects in S3 or S3-compatible object storage (OVHcloud, SeaweedFS, Ceph,
Garage, ...). The S3 buckets appear in Yamcs like the other buckets: in the web interface, the HTTP API, the CFDP
and file transfer services, etc.

It requires Yamcs 5.13.7 or later.

## Installation

```
mvn package
```

Copy `target/s3-buckets-<version>.jar` and all the jars from `target/lib/` to the `lib/` directory of the Yamcs
installation. `target/lib/` contains the AWS SDK for Java v2 (about 30 jars, 9 MB); the Netty and slf4j libraries
used by the SDK are already part of Yamcs and are not copied.

## Configuration

In `etc/yamcs.yaml`:

```yaml
bucketProviders:
  - type: s3
    endpoint: https://s3.gra.io.cloud.ovh.net
    region: gra
    accessKeyId: ...
    secretAccessKey: ...
    buckets:
      - name: my-s3-bucket
        localName: telemetry-files
        prefix: yamcs/
```

endpoint (string)
: URL of the S3 service. Not needed for AWS S3 (the endpoint is derived from the region).

region (string)
: **Required.** The S3 region, for example `eu-central-1` (AWS) or `gra` (OVHcloud). S3-compatible servers without
regions usually accept `us-east-1`.

accessKeyId (string), secretAccessKey (string)
: Credentials, to be specified together. If not specified, the default credentials of the AWS SDK are used
(environment variables `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`, `~/.aws/credentials`, container or instance
roles, ...).

pathStyleAccess (boolean)
: Use path-style URLs (`https://endpoint/bucket/key`) instead of virtual-hosted-style URLs
(`https://bucket.endpoint/key`). Some S3-compatible servers require it. Default: `false`.

checksums (string)
: `WHEN_SUPPORTED` or `WHEN_REQUIRED`. With `WHEN_SUPPORTED` the SDK computes CRC checksums for all uploads and
validates them on downloads; not all S3-compatible servers support this. Default: `WHEN_REQUIRED` if an `endpoint`
is specified, `WHEN_SUPPORTED` otherwise.

buckets (list of maps)
: **Required.** The S3 buckets made available in Yamcs. Each entry has the options:

  name (string)
  : **Required.** Name of the S3 bucket. The S3 bucket has to exist.

  localName (string)
  : Name of the bucket in Yamcs. Default: the S3 bucket name. The Yamcs bucket names have to be unique.

  prefix (string)
  : Restrict the Yamcs bucket to the S3 keys starting with this prefix (a "folder" of the S3 bucket). A `/` is
  appended if missing. The Yamcs object names are the S3 keys without the prefix. Several Yamcs buckets can use
  different prefixes of the same S3 bucket. Default: the whole S3 bucket.

At startup, the access to each S3 bucket is verified in the background; a warning is logged if the bucket cannot be
accessed, but Yamcs still starts.

## Limitations

* S3 does not provide the creation time of the objects: the last modification time is shown instead.
* The object listings do not contain the content type and the metadata (S3 does not return them when listing); they
  are available when retrieving a single object.
* The bucket size and the number of objects are computed by listing all the objects, which is slow for large buckets.
* The bucket creation time is shown only if the credentials allow listing the S3 buckets; otherwise 1970-01-01 is
  shown.
* Size and object count limits are not managed by Yamcs: the bucket reports unlimited capacity.
* User metadata is sent as S3 user metadata (`x-amz-meta-*`): the keys are converted to lower case and the total size
  is limited (2 KB on AWS).
* Objects are transferred in one request and kept in memory (as for all Yamcs buckets); the maximum object size is
  5 GB.
* The buckets cannot be created or deleted from Yamcs.

## Development

The tests start a SeaweedFS S3 server with Testcontainers; they are skipped if Docker is not available.

`mvn compile yamcs:run` starts a Yamcs server (http://localhost:8090) with the configuration from
`src/main/yamcs/etc/yamcs.yaml`, by default using a local SeaweedFS server:

```
docker run --rm -p 8333:8333 chrislusf/seaweedfs:4.46 server -s3 -dir=/data -ip.bind=0.0.0.0
curl -X PUT http://localhost:8333/yamcs-dev
```

Another S3 service can be used by setting the environment variables `S3_ENDPOINT`, `S3_REGION`, `S3_ACCESS_KEY_ID`,
`S3_SECRET_ACCESS_KEY` and `S3_BUCKET`.

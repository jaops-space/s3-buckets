# Yamcs S3 Buckets

Yamcs bucket provider storing the bucket objects in S3 or S3-compatible object storage (OVHcloud, SeaweedFS, Ceph,
Garage, ...). The S3 buckets appear in Yamcs like the other buckets: in the web interface, the HTTP API, the CFDP
and file transfer services, etc.

It requires Yamcs 5.14.0 or later.

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
        prefix: files/
```

Or all the buckets in one S3 bucket, each a sub-folder of a folder, created from Yamcs (the Yamcs Web `displays` and
`stacks`, the CFDP `cfdpDown`, those created in Yamcs Web or with the HTTP API):

```yaml
bucketProviders:
  - type: s3
    region: eu-central-1
    createBuckets: true
    folder:
      name: my-s3-bucket
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

createBuckets (boolean)
: A Yamcs option of the bucket providers: the buckets that Yamcs creates are created by this provider, as sub-folders
of `folder` (which it then requires), instead of in the Yamcs database. Only one provider can have it.
Default: `false`.

folder (map)
: An S3 bucket and key prefix whose sub-folders are Yamcs buckets: `<prefix><bucket>/<object>`. Those present at
startup are loaded; a bucket created from Yamcs (`createBuckets`) gets its `.yamcs-bucket` object (see Limits),
which keeps it while empty (an existing sub-folder of that name becomes the bucket, with its objects and limits);
deleting an (empty) bucket from Yamcs deletes its sub-folder. Sub-folders whose name is not a valid Yamcs bucket name (letters, digits, `_` and `-`), or is
the `localName` of a bucket in `buckets`, are ignored. Options:

  name (string)
  : **Required.** Name of the S3 bucket. The S3 bucket has to exist.

  prefix (string)
  : Key prefix of the folder. A `/` is appended if missing. Default: the whole S3 bucket.

maxSize (integer), maxObjects (integer)
: The limits of the buckets that have none stored (see Limits). Default: 104857600 (100 MB) and 1000, as the other
Yamcs buckets.

buckets (list of maps)
: The S3 buckets made available in Yamcs; `buckets`, `folder` or both are required. Each entry has the options:

  name (string)
  : **Required.** Name of the S3 bucket. The S3 bucket has to exist.

  localName (string)
  : Name of the bucket in Yamcs. Default: the S3 bucket name. The Yamcs bucket names have to be unique.

  prefix (string)
  : Restrict the Yamcs bucket to the S3 keys starting with this prefix (a "folder" of the S3 bucket). A `/` is
  appended if missing. The Yamcs object names are the S3 keys without the prefix. Several Yamcs buckets can use
  different prefixes of the same S3 bucket. Default: the whole S3 bucket.

At startup, the access to each S3 bucket of `buckets` is verified in the background; a warning is logged if the
bucket cannot be accessed, but Yamcs still starts. The `folder` is listed at startup: Yamcs does not start if it
cannot be listed.

## Limits

Each bucket has a size limit and an object count limit, kept in S3 with the bucket like Yamcs keeps those of its
RocksDB buckets: in the object `.yamcs-bucket` of the bucket (S3 key `<prefix>.yamcs-bucket`), shared by all the Yamcs
servers using the bucket:

```
maxSize=104857600
maxObjects=1000
```

A bucket without it has the provider's `maxSize` and `maxObjects`. Setting a limit in Yamcs (the `maxSize` and
`maxObjects` of a `buckets` entry in `etc/yamcs.yaml`, applied at each start) writes it there; it can also be edited
in S3 directly. `.yamcs-bucket` is not listed with the objects and is a reserved object name.

The limits are read again from S3 before each put and when the bucket properties are retrieved (which the CFDP
service does before accepting a file). A put that would exceed a limit is refused; the usage is computed by listing
the bucket at each put, so two puts at the same time may both pass.

## Limitations

* S3 does not provide the creation time of the objects: the last modification time is shown instead.
* The object listings do not contain the content type and the metadata (S3 does not return them when listing); they
  are available when retrieving a single object.
* The bucket size and the number of objects are computed by listing all the objects, at each put and when the
  properties are retrieved, which is slow for large buckets (1000 objects per S3 request).
* The bucket creation time is shown only if the credentials allow listing the S3 buckets; otherwise 1970-01-01 is
  shown.
* User metadata is sent as S3 user metadata (`x-amz-meta-*`): the keys are converted to lower case and the total size
  is limited (2 KB on AWS).
* Objects are transferred in one request and kept in memory (as for all Yamcs buckets); the maximum object size is
  5 GB.
* Only the buckets of `folder` can be created and deleted from Yamcs; those of `buckets` are configuration.
* The folder is listed at startup only: a bucket created by another Yamcs server sharing the folder appears at the
  next start.

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

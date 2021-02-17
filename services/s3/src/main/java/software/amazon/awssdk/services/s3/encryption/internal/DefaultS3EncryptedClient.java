package software.amazon.awssdk.services.s3.encryption.internal;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.encryption.Content;
import software.amazon.awssdk.services.s3.encryption.S3EncryptedClient;
import software.amazon.awssdk.services.s3.encryption.S3EncryptionRuntime;
import software.amazon.awssdk.services.s3.encryption.model.DecryptContentRequest;
import software.amazon.awssdk.services.s3.encryption.model.DecryptContentResponse;
import software.amazon.awssdk.services.s3.encryption.model.EncryptContentRequest;
import software.amazon.awssdk.services.s3.encryption.model.EncryptContentResponse;
import software.amazon.awssdk.services.s3.encryption.model.ReencryptResponse;
import software.amazon.awssdk.services.s3.encryption.model.ReencryptRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

public class DefaultS3EncryptedClient implements S3EncryptedClient {
    private final S3Client delegate;
    private final S3EncryptionRuntime runtime;

    public DefaultS3EncryptedClient(S3Client delegate, S3EncryptionRuntime runtime) {
        this.delegate = delegate;
        this.runtime = runtime;
    }

    @Override
    public String serviceName() {
        return delegate.serviceName();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public <ReturnT> ReturnT getObject(GetObjectRequest getObjectRequest,
                                       ResponseTransformer<GetObjectResponse, ReturnT> responseTransformer) {
        // TODO: Validate not ranged
        // TODO: Do we need to transform this request at all before sending it?

        return delegate.getObject(getObjectRequest, (getObjectResponse, encryptedStream) -> {
            DecryptContentResponse decryptResponse =
                runtime.decryptContent(DecryptContentRequest.builder()
                                                            .content(Content.create(encryptedStream, getObjectResponse.contentLength()))
                                                            .metadata(getObjectResponse.metadata())
                                                            .build());

            GetObjectResponse decryptedGetObjectResponse =
                getObjectResponse.copy(r -> r.contentLength(decryptResponse.content().contentLength())
                                             .contentType(decryptResponse.contentType())
                                             .contentEncoding(decryptResponse.contentEncoding()));

            return responseTransformer.transform(decryptedGetObjectResponse,
                                                 AbortableInputStream.create(decryptResponse.content().inputStream(),
                                                                             encryptedStream));
        });
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest putObjectRequest, RequestBody requestBody) {
        // TODO: Validate not ranged

        EncryptedContentStreamProvider provider = new EncryptedContentStreamProvider(putObjectRequest, requestBody);

        PutObjectRequest encryptedPutObjectRequest =
            putObjectRequest.copy(r -> r.metadata(provider.initialEncryptResponse.metadata())
                                        .contentLength(provider.initialEncryptResponse.content().contentLength())
                                        .contentType(provider.initialEncryptResponse.contentType())
                                        .contentEncoding(provider.initialEncryptResponse.contentEncoding()));

        // TODO: Do we need to transform this response at all before returning it?
        return delegate.putObject(encryptedPutObjectRequest,
                                  RequestBody.fromContentProvider(provider,
                                                                  encryptedPutObjectRequest.contentLength(),
                                                                  encryptedPutObjectRequest.contentType()));
    }

    @Override
    public ReencryptResponse reencryptObject(ReencryptRequest request) {
        return null; // TODO
    }

    @Override
    public ReencryptResponse reencryptObject(Consumer<ReencryptRequest.Builder> request) {
        return null; // TODO
    }

    private class EncryptedContentStreamProvider implements ContentStreamProvider {
        private final AtomicInteger attempt = new AtomicInteger(0);

        private final EncryptContentResponse initialEncryptResponse;
        private final PutObjectRequest putObjectRequest;
        private final RequestBody requestBody;

        public EncryptedContentStreamProvider(PutObjectRequest putObjectRequest,
                                              RequestBody requestBody) {
            this.putObjectRequest = putObjectRequest;
            this.requestBody = requestBody;
            this.initialEncryptResponse = createNewEncryptResponse();
        }

        @Override
        public InputStream newStream() {
            if (attempt.getAndIncrement() == 0) {
                return initialEncryptResponse.content().inputStream();
            } else {
                return createNewEncryptResponse().content().inputStream();
            }
        }

        private EncryptContentResponse createNewEncryptResponse() {
            ContentStreamProvider contentStreamProvider = requestBody.contentStreamProvider();
            InputStream firstInputStream = contentStreamProvider.newStream();

            return runtime.encryptContent(EncryptContentRequest.builder()
                                                               .content(Content.create(firstInputStream, putObjectRequest.contentLength()))
                                                               .metadata(putObjectRequest.metadata())
                                                               .contentType(putObjectRequest.contentType())
                                                               .contentEncoding(putObjectRequest.contentEncoding())
                                                               .build());
        }
    }
}

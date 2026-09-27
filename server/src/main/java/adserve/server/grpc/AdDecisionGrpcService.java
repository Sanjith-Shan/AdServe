package adserve.server.grpc;

import ads.v1.AdDecisionGrpc;
import ads.v1.AdRequest;
import ads.v1.AdResponse;
import adserve.server.DecisionService;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * The decision endpoint. A shed request fails with RESOURCE_EXHAUSTED and a {@code retry-after-ms}
 * trailer; a request that failed in legacy sync-write mode because the database write failed gets
 * UNAVAILABLE.
 */
public class AdDecisionGrpcService extends AdDecisionGrpc.AdDecisionImplBase {
    public static final Metadata.Key<String> RETRY_AFTER =
            Metadata.Key.of("retry-after-ms", Metadata.ASCII_STRING_MARSHALLER);

    private final DecisionService service;

    public AdDecisionGrpcService(DecisionService service) {
        this.service = service;
    }

    @Override
    public void decide(AdRequest request, StreamObserver<AdResponse> out) {
        DecisionService.Result r;
        try {
            r = service.decide(request);
        } catch (RuntimeException e) {
            out.onError(Status.INTERNAL.withDescription(e.toString()).asRuntimeException());
            return;
        }
        switch (r) {
            case DecisionService.Served s -> {
                out.onNext(s.response());
                out.onCompleted();
            }
            case DecisionService.Shed s -> {
                Metadata trailers = new Metadata();
                trailers.put(RETRY_AFTER, Long.toString(s.retryAfterMs()));
                out.onError(Status.RESOURCE_EXHAUSTED.withDescription("shedding " + request.getPriority())
                        .asRuntimeException(trailers));
            }
            case DecisionService.Failed f -> out.onError(Status.UNAVAILABLE.withDescription(f.reason()).asRuntimeException());
        }
    }
}

package io.atomix.core.set.impl;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import io.atomix.core.collection.CollectionEvent;
import io.atomix.core.collection.CollectionEventListener;
import io.atomix.core.impl.AbstractAsyncPrimitive;
import io.atomix.core.impl.PrimitiveIdDescriptor;
import io.atomix.core.impl.PrimitivePartition;
import io.atomix.core.impl.TranscodingStreamObserver;
import io.atomix.core.iterator.AsyncIterator;
import io.atomix.core.iterator.impl.StreamObserverIterator;
import io.atomix.core.set.AddRequest;
import io.atomix.core.set.AddResponse;
import io.atomix.core.set.AsyncDistributedSet;
import io.atomix.core.set.ClearRequest;
import io.atomix.core.set.ClearResponse;
import io.atomix.core.set.CloseRequest;
import io.atomix.core.set.CloseResponse;
import io.atomix.core.set.ContainsRequest;
import io.atomix.core.set.ContainsResponse;
import io.atomix.core.set.CreateRequest;
import io.atomix.core.set.CreateResponse;
import io.atomix.core.set.DistributedSet;
import io.atomix.core.set.EventRequest;
import io.atomix.core.set.EventResponse;
import io.atomix.core.set.IterateRequest;
import io.atomix.core.set.IterateResponse;
import io.atomix.core.set.KeepAliveRequest;
import io.atomix.core.set.KeepAliveResponse;
import io.atomix.core.set.RemoveRequest;
import io.atomix.core.set.RemoveResponse;
import io.atomix.core.set.SetId;
import io.atomix.core.set.SetServiceGrpc;
import io.atomix.core.set.SizeRequest;
import io.atomix.core.set.SizeResponse;
import io.atomix.core.transaction.TransactionId;
import io.atomix.core.transaction.TransactionLog;
import io.atomix.primitive.PrimitiveManagementService;
import io.atomix.primitive.partition.Partitioner;
import io.atomix.primitive.protocol.DistributedLogProtocol;
import io.atomix.primitive.protocol.MultiPrimaryProtocol;
import io.atomix.primitive.protocol.MultiRaftProtocol;
import io.atomix.utils.concurrent.Futures;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;

/**
 * Default distributed set primitive.
 */
public class DefaultAsyncDistributedSet
    extends AbstractAsyncPrimitive<SetId, AsyncDistributedSet<String>>
    implements AsyncDistributedSet<String> {
  private final SetServiceGrpc.SetServiceStub set;

  public DefaultAsyncDistributedSet(
      SetId id,
      Supplier<Channel> channelFactory,
      PrimitiveManagementService managementService,
      Partitioner<String> partitioner,
      Duration timeout) {
    super(id, SET_ID_DESCRIPTOR, managementService, partitioner, timeout);
    this.set = SetServiceGrpc.newStub(channelFactory.get());
  }

  @Override
  public CompletableFuture<Boolean> add(String element) {
    return addAll(Collections.singleton(element));
  }

  @Override
  public CompletableFuture<Boolean> remove(String element) {
    return removeAll(Collections.singleton(element));
  }

  @Override
  public CompletableFuture<Integer> size() {
    return this.<SizeResponse>execute(observer -> set.size(SizeRequest.newBuilder()
        .setId(id())
        .addAllHeaders(getQueryHeaders())
        .build(), observer))
        .thenCompose(response -> order(response.getSize(), response.getHeadersList()));
  }

  @Override
  public CompletableFuture<Boolean> isEmpty() {
    return size().thenApply(size -> size == 0);
  }

  @Override
  public CompletableFuture<Boolean> contains(String element) {
    return containsAll(Collections.singleton(element));
  }

  @Override
  @SuppressWarnings("unchecked")
  public CompletableFuture<Boolean> addAll(Collection<? extends String> c) {
    return this.<AddResponse>execute(observer -> set.add(AddRequest.newBuilder()
        .setId(id())
        .addAllHeaders(getCommandHeaders())
        .addAllValues((Collection) c)
        .build(), observer))
        .thenCompose(response -> order(response.getAdded(), response.getHeadersList()));
  }

  @Override
  public CompletableFuture<Boolean> containsAll(Collection<? extends String> c) {
    return this.<ContainsResponse>execute(observer -> set.contains(ContainsRequest.newBuilder()
        .setId(id())
        .addAllHeaders(getQueryHeaders())
        .addAllValues((Collection) c)
        .build(), observer))
        .thenCompose(response -> order(response.getContains(), response.getHeadersList()));
  }

  @Override
  public CompletableFuture<Boolean> retainAll(Collection<? extends String> c) {
    return Futures.exceptionalFuture(new UnsupportedOperationException());
  }

  @Override
  public CompletableFuture<Boolean> removeAll(Collection<? extends String> c) {
    return this.<RemoveResponse>execute(observer -> set.remove(RemoveRequest.newBuilder()
        .setId(id())
        .addAllHeaders(getCommandHeaders())
        .addAllValues((Collection) c)
        .build(), observer))
        .thenCompose(response -> order(response.getRemoved(), response.getHeadersList()));
  }

  @Override
  public CompletableFuture<Void> clear() {
    return this.<ClearResponse>execute(observer -> set.clear(ClearRequest.newBuilder()
        .setId(id())
        .addAllHeaders(getCommandHeaders())
        .build(), observer))
        .thenCompose(response -> order(null, response.getHeadersList()));
  }

  @Override
  public CompletableFuture<Void> addListener(CollectionEventListener<String> listener, Executor executor) {
    set.listen(EventRequest.newBuilder()
        .setId(id())
        .addAllHeaders(getCommandHeaders())
        .build(), new StreamObserver<EventResponse>() {
      @Override
      public void onNext(EventResponse response) {
        PrimitivePartition partition = getPartition(response.getHeader().getPartitionId());
        CollectionEvent<String> event = null;
        switch (response.getType()) {
          case ADDED:
            event = new CollectionEvent<>(
                CollectionEvent.Type.ADDED,
                response.getValue());
            break;
          case REMOVED:
            event = new CollectionEvent<>(
                CollectionEvent.Type.REMOVED,
                response.getValue());
            break;
        }
        partition.order(event, response.getHeader()).thenAccept(listener::event);
      }

      @Override
      public void onError(Throwable t) {

      }

      @Override
      public void onCompleted() {

      }
    });
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> removeListener(CollectionEventListener<String> listener) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public AsyncIterator<String> iterator() {
    StreamObserverIterator<String> iterator = new StreamObserverIterator<>();
    set.iterate(IterateRequest.newBuilder()
            .setId(id())
            .addAllHeaders(getQueryHeaders())
            .build(),
        new TranscodingStreamObserver<>(
            iterator,
            IterateResponse::getValue));
    return iterator;
  }

  @Override
  public CompletableFuture<Boolean> prepare(TransactionLog<SetUpdate<String>> transactionLog) {
    return Futures.exceptionalFuture(new UnsupportedOperationException());
  }

  @Override
  public CompletableFuture<Void> commit(TransactionId transactionId) {
    return Futures.exceptionalFuture(new UnsupportedOperationException());
  }

  @Override
  public CompletableFuture<Void> rollback(TransactionId transactionId) {
    return Futures.exceptionalFuture(new UnsupportedOperationException());
  }

  @Override
  public CompletableFuture<AsyncDistributedSet<String>> connect() {
    return this.<CreateResponse>execute(stream -> set.create(CreateRequest.newBuilder()
        .setId(id())
        .setTimeout(com.google.protobuf.Duration.newBuilder()
            .setSeconds(timeout.getSeconds())
            .setNanos(timeout.getNano())
            .build())
        .build(), stream))
        .thenAccept(response -> {
          startKeepAlive(response.getHeadersList());
        })
        .thenApply(v -> this);
  }

  @Override
  protected CompletableFuture<Void> keepAlive() {
    return this.<KeepAliveResponse>execute(stream -> set.keepAlive(KeepAliveRequest.newBuilder()
        .setId(id())
        .addAllHeaders(getSessionHeaders())
        .build(), stream))
        .thenAccept(response -> completeKeepAlive(response.getHeadersList()));
  }

  @Override
  public CompletableFuture<Void> close() {
    return this.<CloseResponse>execute(stream -> set.close(CloseRequest.newBuilder()
        .setId(id())
        .addAllHeaders(getSessionHeaders())
        .build(), stream))
        .thenApply(response -> null);
  }

  @Override
  public CompletableFuture<Void> delete() {
    return Futures.exceptionalFuture(new UnsupportedOperationException());
  }

  @Override
  public DistributedSet<String> sync(Duration operationTimeout) {
    return new BlockingDistributedSet<>(this, operationTimeout.toMillis());
  }

  private static final PrimitiveIdDescriptor<SetId> SET_ID_DESCRIPTOR = new PrimitiveIdDescriptor<SetId>() {
    @Override
    public String getName(SetId id) {
      return id.getName();
    }

    @Override
    public boolean hasMultiRaftProtocol(SetId id) {
      return id.hasRaft();
    }

    @Override
    public MultiRaftProtocol getMultiRaftProtocol(SetId id) {
      return id.getRaft();
    }

    @Override
    public boolean hasMultiPrimaryProtocol(SetId id) {
      return id.hasMultiPrimary();
    }

    @Override
    public MultiPrimaryProtocol getMultiPrimaryProtocol(SetId id) {
      return id.getMultiPrimary();
    }

    @Override
    public boolean hasDistributedLogProtocol(SetId id) {
      return id.hasLog();
    }

    @Override
    public DistributedLogProtocol getDistributedLogProtocol(SetId id) {
      return id.getLog();
    }
  };
}

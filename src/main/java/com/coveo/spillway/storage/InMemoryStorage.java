/**
 * The MIT License
 * Copyright (c) 2016 Coveo
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.coveo.spillway.storage;

import com.coveo.spillway.limit.LimitKey;
import com.coveo.spillway.storage.utils.AddAndGetRequest;
import com.coveo.spillway.storage.utils.Capacity;
import com.coveo.spillway.storage.utils.OverrideKeyRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Implementation of {@link LimitUsageStorage} using memory.
 * <p>
 * Not recommended as a distributed storage solution since sharing memory
 * can be complicated. Perfect for local usages.
 *
 * @author Guillaume Simard
 * @author Emile Fugulin
 * @author Simon Toussaint
 * @since 1.0.0
 */
public class InMemoryStorage implements LimitUsageStorage {

  Map<LimitKey, Capacity> map = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemDefaultZone();

  @Override
  public Map<LimitKey, Integer> addAndGet(Collection<AddAndGetRequest> requests) {
    Map<LimitKey, Integer> updatedEntries = new HashMap<>();

    for (AddAndGetRequest request : requests) {
      LimitKey limitKey = LimitKey.fromRequest(request);

      Capacity counter = map.computeIfAbsent(limitKey, (key) -> new Capacity());
      updatedEntries.put(limitKey, counter.addAndGet(request.getCost()));
    }
    removeExpiredEntries();

    return updatedEntries;
  }

  @Override
  public Map<LimitKey, Integer> addAndGetWithLimit(Collection<AddAndGetRequest> requests) {
    Map<LimitKey, Integer> updatedEntries = new HashMap<>();
    requests.forEach(
        request -> {
          LimitKey limitKey = LimitKey.fromRequest(request);
          LimitKey previousLimitKey = LimitKey.previousLimitKeyFromRequest(request);
          Capacity counter = map.computeIfAbsent(limitKey, (key) -> new Capacity());
          int previousCounter =
              (int)
                  Math.ceil(
                      (map.get(previousLimitKey) != null ? map.get(previousLimitKey).get() : 0)
                          * request.getPreviousBucketCounterPercentage());
          updatedEntries.put(
              limitKey,
              counter.addAndGetWithLimit(request.getCost(), request.getLimit(), previousCounter)
                  + previousCounter);
          removeExpiredEntries(request.getEventTimestamp());
        });
    return updatedEntries;
  }

  @Override
  public void close() {}

  public void overrideKeys(List<OverrideKeyRequest> overrides) {
    for (OverrideKeyRequest override : overrides) {
      map.put(override.getLimitKey(), new Capacity(override.getNewValue()));
    }
    removeExpiredEntries();
  }

  public void applyOnEach(Consumer<Entry<LimitKey, Capacity>> action) {
    map.entrySet().forEach(action);
  }

  @Override
  public Map<LimitKey, Integer> getCurrentLimitCounters() {
    removeExpiredEntries();
    return map.entrySet()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, kvp -> kvp.getValue().get()));
  }

  @Override
  public Map<LimitKey, Integer> getCurrentLimitCounters(String resource) {
    removeExpiredEntries();
    return filterLimitCountersBy(e -> e.getKey().getResource().equals(resource));
  }

  @Override
  public Map<LimitKey, Integer> getCurrentLimitCounters(String resource, String limitName) {
    removeExpiredEntries();
    return filterLimitCountersBy(
        e -> e.getKey().getResource().equals(resource),
        e -> e.getKey().getLimitName().equals(limitName));
  }

  @Override
  public Map<LimitKey, Integer> getCurrentLimitCounters(
      String resource, String limitName, String property) {
    removeExpiredEntries();
    return filterLimitCountersBy(
        e -> e.getKey().getResource().equals(resource),
        e -> e.getKey().getLimitName().equals(limitName),
        e -> e.getKey().getProperty().equals(property));
  }

  private Map<LimitKey, Integer> filterLimitCountersBy(
      Predicate<Map.Entry<LimitKey, Capacity>>... predicates) {
    return Collections.unmodifiableMap(
        map.entrySet()
            .stream()
            .filter(Arrays.stream(predicates).reduce(Predicate::and).orElse(x -> true))
            .collect(Collectors.toMap(Map.Entry::getKey, kvp -> kvp.getValue().get())));
  }

  private void removeExpiredEntries() {
    Instant now = Instant.now(clock);

    Set<LimitKey> expiredLimitKeys =
        map.keySet()
            .stream()
            .filter(limitKey -> limitKey.getBucket().plus(limitKey.getExpiration()).isBefore(now))
            .collect(Collectors.toSet());

    map.keySet().removeAll(expiredLimitKeys);
  }

  private void removeExpiredEntries(Instant timestamp) {
    Set<LimitKey> expiredLimitKeys =
        map.keySet()
            .stream()
            .filter(
                limitKey
                    -> limitKey
                        .getBucket()
                        .plus(limitKey.getExpiration().multipliedBy(2))
                        .isBefore(timestamp))
            .collect(Collectors.toSet());
    map.keySet().removeAll(expiredLimitKeys);
  }
}

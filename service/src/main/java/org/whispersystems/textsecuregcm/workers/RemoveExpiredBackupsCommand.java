/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.workers;

import io.dropwizard.core.Application;
import io.dropwizard.core.cli.Cli;
import io.dropwizard.core.cli.EnvironmentCommand;
import io.dropwizard.core.setup.Environment;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.WhisperServerConfiguration;
import org.whispersystems.textsecuregcm.backup.BackupManager;
import org.whispersystems.textsecuregcm.backup.ExpiredBackup;
import org.whispersystems.textsecuregcm.metrics.MetricsUtil;
import org.whispersystems.textsecuregcm.util.logging.UncaughtExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class RemoveExpiredBackupsCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private static final String SEGMENT_COUNT_ARGUMENT = "segments";
  private static final String DRY_RUN_ARGUMENT = "dry-run";
  private static final String MAX_CONCURRENCY_ARGUMENT = "max-concurrency";
  private static final String GRACE_PERIOD_ARGUMENT = "grace-period";

  // A backup that has not been refreshed after a grace period is eligible for deletion
  private static final Duration DEFAULT_GRACE_PERIOD = Duration.ofDays(60);
  private static final int DEFAULT_SEGMENT_COUNT = 1;
  private static final int DEFAULT_CONCURRENCY = 16;

  private static final String EXPIRED_BACKUPS_COUNTER_NAME = MetricsUtil.name(RemoveExpiredBackupsCommand.class,
      "expiredBackups");

  private final Clock clock;

  public RemoveExpiredBackupsCommand(final Clock clock) {
    super(new Application<>() {
      @Override
      public void run(final WhisperServerConfiguration configuration, final Environment environment) {
      }
    }, "remove-expired-backups", "Removes backups that have expired");
    this.clock = clock;
  }

  @Override
  public void configure(final Subparser subparser) {
    super.configure(subparser);

    subparser.addArgument("--segments")
        .type(Integer.class)
        .dest(SEGMENT_COUNT_ARGUMENT)
        .required(false)
        .setDefault(DEFAULT_SEGMENT_COUNT)
        .help("The total number of segments for a DynamoDB scan");

    subparser.addArgument("--grace-period")
        .type(Long.class)
        .dest(GRACE_PERIOD_ARGUMENT)
        .required(false)
        .setDefault(DEFAULT_GRACE_PERIOD.getSeconds())
        .help("The number of seconds after which a backup is eligible for removal");

    subparser.addArgument("--max-concurrency")
        .type(Integer.class)
        .dest(MAX_CONCURRENCY_ARGUMENT)
        .required(false)
        .setDefault(DEFAULT_CONCURRENCY)
        .help("Max concurrency for backup expirations. Each expiration may do multiple cdn operations");

    subparser.addArgument("--dry-run")
        .type(Boolean.class)
        .dest(DRY_RUN_ARGUMENT)
        .required(false)
        .setDefault(true)
        .help("If true, don’t actually remove expired backups");
  }

  @Override
  protected void run(final Environment environment, final Namespace namespace,
      final WhisperServerConfiguration configuration) throws Exception {

    UncaughtExceptionHandler.register();
    final CommandDependencies commandDependencies = CommandDependencies.build(getName(), environment, configuration);
    MetricsUtil.configureRegistries(configuration, environment, commandDependencies.dynamicConfigurationManager());

    final int segments = Objects.requireNonNull(namespace.getInt(SEGMENT_COUNT_ARGUMENT));
    final int concurrency = Objects.requireNonNull(namespace.getInt(MAX_CONCURRENCY_ARGUMENT));
    final boolean dryRun = namespace.getBoolean(DRY_RUN_ARGUMENT);
    final Duration gracePeriod = Duration.ofSeconds(Objects.requireNonNull(namespace.getLong(GRACE_PERIOD_ARGUMENT)));

    logger.info("Crawling backups with {} segments and {} processors, grace period {}",
        segments,
        Runtime.getRuntime().availableProcessors(),
        gracePeriod);

    try {
      environment.lifecycle().getManagedObjects().forEach(managedObject -> {
        try {
          managedObject.start();
        } catch (final Exception e) {
          logger.error("Failed to start managed object", e);
          throw new RuntimeException(e);
        }
      });
      final AtomicLong backupsExpired = new AtomicLong();
      final BackupManager backupManager = commandDependencies.backupManager();
      backupManager
          .getExpiredBackups(segments, Schedulers.parallel(), clock.instant().plus(gracePeriod))
          .flatMap(expiredBackup -> removeExpiredBackup(backupManager, expiredBackup, dryRun), concurrency)
          .doOnNext(ignored -> backupsExpired.incrementAndGet())
          .then()
          .block();
      logger.info("Expired {} backups", backupsExpired.get());
    } finally {
      environment.lifecycle().getManagedObjects().forEach(managedObject -> {
        try {
          managedObject.stop();
        } catch (final Exception e) {
          logger.error("Failed to stop managed object", e);
        }
      });
    }
  }

  private Mono<Void> removeExpiredBackup(
      final BackupManager backupManager, final ExpiredBackup expiredBackup,
      final boolean dryRun) {

    final Mono<Void> mono;
    if (dryRun) {
      mono = Mono.empty();
    } else {
      mono = Mono.fromCompletionStage(() ->
          backupManager.deleteBackup(expiredBackup.backupTierToRemove(), expiredBackup.hashedBackupId()));
    }

    return mono
        .doOnSuccess(ignored -> Metrics
            .counter(EXPIRED_BACKUPS_COUNTER_NAME,
                "tier", expiredBackup.backupTierToRemove().name(),
                "dryRun", String.valueOf(dryRun))
            .increment())
        .onErrorResume(throwable -> {
          logger.warn("Failed to remove tier {} for backup {}", expiredBackup.backupTierToRemove(),
              expiredBackup.hashedBackupId());
          return Mono.empty();
        });
  }

  @Override
  public void onError(final Cli cli, final Namespace namespace, final Throwable throwable) {
    logger.error("Unhandled error", throwable);
  }
}

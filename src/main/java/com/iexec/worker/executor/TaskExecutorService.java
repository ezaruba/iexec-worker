package com.iexec.worker.executor;

import com.iexec.common.chain.ChainReceipt;
import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.dapp.DappType;
import com.iexec.common.replicate.AvailableReplicateModel;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.worker.chain.ContributionService;
import com.iexec.worker.docker.DockerComputationService;
import com.iexec.worker.feign.CustomFeignClient;
import com.iexec.worker.result.ResultInfo;
import com.iexec.worker.result.ResultService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Sign;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static com.iexec.common.replicate.ReplicateStatus.*;

@Slf4j
@Service
public class TaskExecutorService {

    // external services
    private DockerComputationService dockerComputationService;
    private ResultService resultService;
    private ContributionService contributionService;
    private CustomFeignClient feignClient;

    // internal variables
    private int maxNbExecutions;
    private ThreadPoolExecutor executor;

    public TaskExecutorService(DockerComputationService dockerComputationService,
                               ContributionService contributionService,
                               ResultService resultService,
                               CustomFeignClient feignClient) {
        this.dockerComputationService = dockerComputationService;
        this.resultService = resultService;
        this.contributionService = contributionService;
        this.feignClient = feignClient;

        maxNbExecutions = Runtime.getRuntime().availableProcessors() - 1;
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(maxNbExecutions);
    }

    public boolean canAcceptMoreReplicate() {
        return executor.getActiveCount() < maxNbExecutions;
    }

    public void addReplicate(AvailableReplicateModel replicateModel) {
        ContributionAuthorization contribAuth = replicateModel.getContributionAuthorization();

        CompletableFuture.supplyAsync(() -> executeTask(replicateModel), executor)
                .thenAccept(resultInfo -> tryToContribute(contribAuth, resultInfo));
    }

    private ResultInfo executeTask(AvailableReplicateModel replicateModel) {
        String chainTaskId = replicateModel.getContributionAuthorization().getChainTaskId();

        if (contributionService.isChainTaskInitialized(chainTaskId)) {
            feignClient.updateReplicateStatus(chainTaskId, RUNNING);

            if (replicateModel.getAppType().equals(DappType.DOCKER)) {

                feignClient.updateReplicateStatus(chainTaskId, APP_DOWNLOADING);
                boolean isImagePulled = dockerComputationService.dockerPull(chainTaskId, replicateModel.getAppUri());
                if (isImagePulled) {
                    feignClient.updateReplicateStatus(chainTaskId, APP_DOWNLOADED);
                } else {
                    feignClient.updateReplicateStatus(chainTaskId, APP_DOWNLOAD_FAILED);
                    return new ResultInfo();
                }

                feignClient.updateReplicateStatus(chainTaskId, COMPUTING);
                try {
                    ResultInfo resultInfo = dockerComputationService.dockerRun(replicateModel);
                    resultService.addResultInfo(chainTaskId, resultInfo);
                    feignClient.updateReplicateStatus(chainTaskId, COMPUTED);
                    return resultInfo;
                } catch (Exception e) {
                    log.error("Error in the run of the application [error:{}]", e.getMessage());
                }
            }
        } else {
            log.warn("Task NOT initialized on chain [chainTaskId:{}]", chainTaskId);
        }
        return new ResultInfo();
    }

    private void tryToContribute(ContributionAuthorization contribAuth, ResultInfo resultInfo) {
        if (resultInfo.getDeterministHash().isEmpty()) {
            return;
        }
        String chainTaskId = contribAuth.getChainTaskId();
        Sign.SignatureData enclaveSignatureData = contributionService.getEnclaveSignatureData(contribAuth, resultInfo);

        Optional<ReplicateStatus> canContributeStatus = contributionService.getCanContributeStatus(chainTaskId);
        if (!canContributeStatus.isPresent()){
            log.error("canContributeStatus should not be empty (getChainTask issue) [chainTaskId:{}]", chainTaskId);
            return;
        }

        feignClient.updateReplicateStatus(chainTaskId, canContributeStatus.get());
        if (!canContributeStatus.get().equals(CAN_CONTRIBUTE) & enclaveSignatureData != null) {
            log.warn("Cant contribute [chainTaskId:{}, status:{}]", chainTaskId, canContributeStatus.get());
            return;
        }

        if (!contributionService.hasEnoughGas()) {
            feignClient.updateReplicateStatus(chainTaskId, OUT_OF_GAS);
            System.exit(0);
        }

        feignClient.updateReplicateStatus(chainTaskId, CONTRIBUTING);

        ChainReceipt chainReceipt = contributionService.contribute(contribAuth, resultInfo.getDeterministHash(), enclaveSignatureData);
        if (chainReceipt == null) {
            feignClient.updateReplicateStatus(chainTaskId, CONTRIBUTE_FAILED);
            return;
        }

        feignClient.updateReplicateStatus(chainTaskId, CONTRIBUTED, chainReceipt);
    }
}

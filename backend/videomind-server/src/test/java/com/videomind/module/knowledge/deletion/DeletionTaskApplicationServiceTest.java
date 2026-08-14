package com.videomind.module.knowledge.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.ProcessingTaskState;
import com.videomind.common.enums.ProcessingTaskType;
import com.videomind.common.exception.BizException;
import com.videomind.module.knowledge.entity.KnowledgeBase;
import com.videomind.module.knowledge.mapper.KnowledgeBaseMapper;
import com.videomind.module.task.entity.ProcessingTask;
import com.videomind.module.task.mapper.ProcessingTaskMapper;
import com.videomind.module.task.mq.TaskCreateCommand;
import com.videomind.module.task.mq.TaskDispatchResult;
import com.videomind.module.task.mq.TransactionalTaskMessageProducer;
import com.videomind.module.task.service.ProcessingTaskStateMachine;
import com.videomind.module.task.service.TaskCheckpointService;
import com.videomind.module.video.entity.VideoFile;
import com.videomind.module.video.service.VideoFileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeletionTaskApplicationServiceTest {
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final VideoFileService videos = mock(VideoFileService.class);
    private final TransactionalTaskMessageProducer messages = mock(TransactionalTaskMessageProducer.class);
    private final ProcessingTaskMapper processingTasks = mock(ProcessingTaskMapper.class);
    private final ProcessingTaskStateMachine stateMachine = mock(ProcessingTaskStateMachine.class);
    private final TaskCheckpointService checkpoints = mock(TaskCheckpointService.class);
    private final DeletionTaskApplicationService service = new DeletionTaskApplicationService(
            knowledgeBases, videos, messages, processingTasks, stateMachine, checkpoints);

    @Test
    void dispatchesOwnedUserKnowledgeBaseAsTransactionalDeleteTask() {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(11L);
        base.setUserId(7L);
        base.setType(KnowledgeBaseType.USER);
        when(knowledgeBases.selectOne(any())).thenReturn(base);
        when(messages.dispatch(any())).thenReturn(new TaskDispatchResult("event-1", 99L, 11L, false));
        when(processingTasks.selectById(99L)).thenReturn(task(ProcessingTaskType.KNOWLEDGE_DELETE,
                ProcessingTaskState.PENDING));

        var response = service.deleteKnowledgeBase(7L, 11L);

        assertThat(response.eventId()).isEqualTo("event-1");
        assertThat(response.taskId()).isEqualTo(99L);
        assertThat(response.status()).isEqualTo("PENDING");
        ArgumentCaptor<TaskCreateCommand> command = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(messages).dispatch(command.capture());
        assertThat(command.getValue().taskType()).isEqualTo(ProcessingTaskType.KNOWLEDGE_DELETE);
        assertThat(command.getValue().businessFingerprint()).isEqualTo("KNOWLEDGE_DELETE:7:11");
    }

    @Test
    void rejectsKnowledgeDeletionWithoutOwnershipBeforeSendingMessage() {
        when(knowledgeBases.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.deleteKnowledgeBase(7L, 11L))
                .isInstanceOfSatisfying(BizException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(404));
        verify(messages, never()).dispatch(any());
    }

    @Test
    void videoKnowledgeBaseCannotBeDeletedIndependently() {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(11L);
        base.setUserId(7L);
        base.setType(KnowledgeBaseType.VIDEO);
        when(knowledgeBases.selectOne(any())).thenReturn(base);

        assertThatThrownBy(() -> service.deleteKnowledgeBase(7L, 11L))
                .isInstanceOfSatisfying(BizException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(409));
        verify(messages, never()).dispatch(any());
    }

    @Test
    void dispatchesOwnedVideoAsTransactionalDeleteTask() {
        when(videos.getVideoDetail(15L, 7L)).thenReturn(new VideoFile());
        when(messages.dispatch(any())).thenReturn(new TaskDispatchResult("event-2", 100L, 15L, false));
        when(processingTasks.selectById(100L)).thenReturn(task(ProcessingTaskType.VIDEO_DELETE,
                ProcessingTaskState.PENDING));

        assertThat(service.deleteVideo(7L, 15L).taskId()).isEqualTo(100L);

        ArgumentCaptor<TaskCreateCommand> command = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(messages).dispatch(command.capture());
        assertThat(command.getValue().taskType()).isEqualTo(ProcessingTaskType.VIDEO_DELETE);
    }

    @Test
    void refusesCancellationAfterDestructiveCheckpoint() {
        when(processingTasks.selectById(99L)).thenReturn(task(ProcessingTaskType.KNOWLEDGE_DELETE,
                ProcessingTaskState.PROCESSING));
        when(checkpoints.isCompleted(99L, PhysicalDeletionCoordinator.DELETION_STARTED)).thenReturn(true);

        assertThatThrownBy(() -> service.cancelIfDeletionTask(7L, 99L))
                .isInstanceOfSatisfying(BizException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(409));
        verify(stateMachine, never()).requestCancel(any(), any());
    }

    @Test
    void cancellationBeforeDestructiveCheckpointIsIdempotent() {
        ProcessingTask pending = task(ProcessingTaskType.VIDEO_DELETE, ProcessingTaskState.PENDING);
        ProcessingTask cancelled = task(ProcessingTaskType.VIDEO_DELETE, ProcessingTaskState.CANCELLED);
        when(processingTasks.selectById(99L)).thenReturn(pending, cancelled);
        when(stateMachine.requestCancel(99L, 7L)).thenReturn(
                new ProcessingTaskStateMachine.CancelRequestResult(
                        ProcessingTaskStateMachine.CancelRequestStatus.CANCELLED, 1));

        var result = service.cancelIfDeletionTask(7L, 99L).orElseThrow();

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(stateMachine).requestCancel(99L, 7L);
    }

    private ProcessingTask task(ProcessingTaskType type, ProcessingTaskState state) {
        ProcessingTask task = new ProcessingTask();
        task.setId(99L);
        task.setEventId("event-1");
        task.setUserId(7L);
        task.setTaskType(type);
        task.setState(state);
        return task;
    }
}

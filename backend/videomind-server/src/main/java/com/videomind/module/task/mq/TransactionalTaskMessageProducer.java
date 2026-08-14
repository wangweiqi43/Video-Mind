package com.videomind.module.task.mq;

public interface TransactionalTaskMessageProducer {
    TaskDispatchResult dispatch(TaskCreateCommand command);
}

package com.videomind.module.task.service;

import com.videomind.module.task.mq.TaskDispatchResult;
import com.videomind.module.task.mq.TaskTransactionContext;

public interface LocalTaskTransactionService {
    TaskDispatchResult createOrReuse(TaskTransactionContext context);
}

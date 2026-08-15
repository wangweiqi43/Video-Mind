package com.videomind.module.knowledge.deletion;

import com.videomind.module.knowledge.deletion.DeletionManifest.ObjectRef;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PhysicalDeletionRepository {
    private final JdbcTemplate jdbc;

    public DeletionManifest knowledgeManifest(Long userId, Long knowledgeBaseId) {
        Long owned = singleLong("SELECT id FROM knowledge_base WHERE id=? AND user_id=? AND deleted=0",
                knowledgeBaseId, userId);
        if (owned == null) {
            throw new IllegalStateException("KNOWLEDGE_DELETE_TARGET_MISSING");
        }
        List<Long> documents = longs(
                "SELECT id FROM knowledge_document WHERE knowledge_base_id=? AND user_id=? AND deleted=0",
                knowledgeBaseId, userId);
        Set<ObjectRef> objects = new LinkedHashSet<>();
        addDocumentObjects(objects, userId, knowledgeBaseId);
        List<Long> conversations = longs("""
                SELECT id FROM chat_session
                WHERE user_id=? AND deleted=0
                  AND JSON_CONTAINS(knowledge_base_ids_json, JSON_ARRAY(?))=1
                """, userId, knowledgeBaseId);
        return new DeletionManifest(userId, knowledgeBaseId, knowledgeBaseId, null,
                documents, List.copyOf(objects), conversations, List.of());
    }

    public DeletionManifest videoManifest(Long userId, Long videoId) {
        List<VideoRoot> roots = jdbc.query("""
                SELECT id, minio_bucket, minio_object_key FROM video_file
                WHERE id=? AND user_id=? AND deleted=0
                """, (rs, row) -> new VideoRoot(rs.getLong("id"), rs.getString("minio_bucket"),
                rs.getString("minio_object_key")), videoId, userId);
        if (roots.isEmpty()) {
            throw new IllegalStateException("VIDEO_DELETE_TARGET_MISSING");
        }
        Long knowledgeBaseId = singleLong("""
                SELECT id FROM knowledge_base
                WHERE video_id=? AND user_id=? AND type='VIDEO' AND deleted=0
                """, videoId, userId);
        List<Long> documents = knowledgeBaseId == null ? List.of() : longs(
                "SELECT id FROM knowledge_document WHERE knowledge_base_id=? AND user_id=? AND deleted=0",
                knowledgeBaseId, userId);
        Set<ObjectRef> objects = new LinkedHashSet<>();
        VideoRoot root = roots.get(0);
        add(objects, root.bucket(), root.objectKey());
        if (knowledgeBaseId != null) {
            addDocumentObjects(objects, userId, knowledgeBaseId);
        }
        addRows(objects, "SELECT bucket, markdown_object_key AS object_key FROM video_timeline "
                + "WHERE video_id=? AND user_id=?", videoId, userId);
        addRows(objects, "SELECT bucket, event_json_object_key AS object_key FROM video_timeline "
                + "WHERE video_id=? AND user_id=?", videoId, userId);
        addRows(objects, "SELECT bucket, markdown_object_key AS object_key FROM video_report "
                + "WHERE video_id=? AND user_id=?", videoId, userId);
        addRows(objects, "SELECT bucket, json_object_key AS object_key FROM video_report "
                + "WHERE video_id=? AND user_id=?", videoId, userId);
        List<Long> conversations = longs(
                "SELECT id FROM chat_session WHERE video_id=? AND user_id=? AND deleted=0", videoId, userId);
        List<String> uploads = strings(
                "SELECT upload_id FROM video_upload_session WHERE video_id=? AND user_id=?", videoId, userId);
        return new DeletionManifest(userId, videoId, knowledgeBaseId, videoId,
                documents, List.copyOf(objects), conversations, uploads);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeRows(DeletionManifest manifest) {
        deleteKnowledgeTree(manifest.userId(), manifest.knowledgeBaseId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteVideoRows(DeletionManifest manifest) {
        Long userId = manifest.userId();
        Long videoId = manifest.videoId();
        if (manifest.knowledgeBaseId() != null) {
            deleteKnowledgeTree(userId, manifest.knowledgeBaseId());
        }
        jdbc.update("DELETE s FROM agent_step s JOIN agent_execution e ON e.id=s.execution_id "
                + "JOIN chat_generation g ON g.id=e.generation_id JOIN chat_session c "
                + "ON c.id=g.conversation_id WHERE c.video_id=? AND c.user_id=?", videoId, userId);
        jdbc.update("DELETE e FROM agent_execution e JOIN chat_generation g ON g.id=e.generation_id "
                + "JOIN chat_session c ON c.id=g.conversation_id WHERE c.video_id=? AND c.user_id=?",
                videoId, userId);
        jdbc.update("DELETE g FROM chat_generation g JOIN chat_session c ON c.id=g.conversation_id "
                + "WHERE c.video_id=? AND c.user_id=?", videoId, userId);
        jdbc.update("DELETE ck FROM conversation_knowledge_base ck JOIN chat_session c "
                + "ON c.id=ck.conversation_id WHERE c.video_id=? AND c.user_id=?", videoId, userId);
        jdbc.update("DELETE m FROM chat_message m JOIN chat_session c ON c.id=m.session_id "
                + "WHERE c.video_id=? AND c.user_id=?", videoId, userId);
        jdbc.update("DELETE s FROM conversation_summary s JOIN chat_session c ON c.id=s.conversation_id "
                + "WHERE c.video_id=? AND c.user_id=?", videoId, userId);
        jdbc.update("DELETE FROM chat_session WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM video_asr_chunk WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM video_asr_segment WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM video_ocr_observation WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM video_timeline WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM video_report WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM video_transcription WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM ai_summary_result WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM task_record WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM video_upload_session WHERE video_id=? AND user_id=?", videoId, userId);
        jdbc.update("DELETE FROM video_file WHERE id=? AND user_id=?", videoId, userId);
    }

    private void deleteKnowledgeTree(Long userId, Long knowledgeBaseId) {
        jdbc.update("DELETE a FROM document_asset a JOIN document_version v ON v.id=a.document_version_id "
                + "JOIN knowledge_document d ON d.id=v.document_id "
                + "WHERE d.knowledge_base_id=? AND d.user_id=?", knowledgeBaseId, userId);
        jdbc.update("DELETE FROM document_chunk WHERE knowledge_base_id=? AND user_id=?", knowledgeBaseId, userId);
        jdbc.update("DELETE v FROM document_version v JOIN knowledge_document d ON d.id=v.document_id "
                + "WHERE d.knowledge_base_id=? AND d.user_id=?", knowledgeBaseId, userId);
        jdbc.update("DELETE FROM knowledge_document WHERE knowledge_base_id=? AND user_id=?",
                knowledgeBaseId, userId);
        jdbc.update("DELETE FROM conversation_knowledge_base WHERE knowledge_base_id=?", knowledgeBaseId);
        jdbc.update("DELETE FROM knowledge_base WHERE id=? AND user_id=?", knowledgeBaseId, userId);
    }

    private void addDocumentObjects(Set<ObjectRef> target, Long userId, Long knowledgeBaseId) {
        addRows(target, """
                SELECT v.original_bucket AS bucket, v.original_object_key AS object_key
                FROM document_version v JOIN knowledge_document d ON d.id=v.document_id
                WHERE d.knowledge_base_id=? AND d.user_id=?
                """, knowledgeBaseId, userId);
        addRows(target, """
                SELECT v.markdown_bucket AS bucket, v.markdown_object_key AS object_key
                FROM document_version v JOIN knowledge_document d ON d.id=v.document_id
                WHERE d.knowledge_base_id=? AND d.user_id=?
                """, knowledgeBaseId, userId);
        addRows(target, """
                SELECT a.bucket, a.object_key FROM document_asset a
                JOIN document_version v ON v.id=a.document_version_id
                JOIN knowledge_document d ON d.id=v.document_id
                WHERE d.knowledge_base_id=? AND d.user_id=?
                """, knowledgeBaseId, userId);
    }

    private void addRows(Set<ObjectRef> target, String sql, Object... args) {
        List<ObjectRef> values = jdbc.query(sql,
                (rs, row) -> new ObjectRef(rs.getString("bucket"), rs.getString("object_key")), args);
        values.forEach(value -> add(target, value.bucket(), value.objectKey()));
    }

    private static void add(Set<ObjectRef> target, String bucket, String objectKey) {
        if (bucket != null && !bucket.isBlank() && objectKey != null && !objectKey.isBlank()) {
            target.add(new ObjectRef(bucket, objectKey));
        }
    }

    private Long singleLong(String sql, Object... args) {
        List<Long> values = longs(sql, args);
        return values.isEmpty() ? null : values.get(0);
    }

    private List<Long> longs(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> rs.getLong(1), args);
    }

    private List<String> strings(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> rs.getString(1), args);
    }

    private record VideoRoot(Long id, String bucket, String objectKey) { }
}

package com.videomind.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videomind.module.knowledge.entity.DocumentChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {
    @Insert("""
            INSERT IGNORE INTO document_chunk
                (embedding_id, user_id, knowledge_base_id, document_id, document_version_id,
                 source_type, chunk_index, parent_index, child_index, heading, content,
                 parent_content, start_offset, end_offset, start_ms, end_ms, published, created_time)
            VALUES
                (#{embeddingId}, #{userId}, #{knowledgeBaseId}, #{documentId}, #{documentVersionId},
                 #{sourceType}, #{chunkIndex}, #{parentIndex}, #{childIndex}, #{heading}, #{content},
                 #{parentContent}, #{startOffset}, #{endOffset}, #{startMs}, #{endMs}, #{published}, #{createdTime})
            """)
    int insertIgnore(DocumentChunk chunk);

    @Update("""
            UPDATE document_chunk SET published = 1
             WHERE document_version_id = #{documentVersionId} AND published = 0
            """)
    int publishVersion(@Param("documentVersionId") Long documentVersionId);
}

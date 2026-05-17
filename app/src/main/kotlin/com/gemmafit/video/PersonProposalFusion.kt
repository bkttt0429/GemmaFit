package com.gemmafit.video

import kotlin.math.max
import kotlin.math.min

internal object PersonProposalFusion {
    const val MIN_PROPOSAL_SCORE = 0.25f
    private const val MIN_ANCHOR_IOU = 0.02f
    private const val MIN_CANDIDATE_IOU = 0.02f

    fun bestProposalForAnchor(
        proposals: List<PersonProposal>,
        anchor: PoseBoundingBox?,
    ): PersonProposal? {
        val usable = proposals.filter { it.score >= MIN_PROPOSAL_SCORE && it.bbox.area > 0f }
        if (usable.isEmpty()) return null
        if (anchor == null || anchor.area <= 0f) return usable.maxByOrNull { it.score }
        return usable
            .map { proposal ->
                val anchorIou = iou(anchor, proposal.bbox)
                proposal to (0.70f * anchorIou + 0.30f * proposal.score)
            }
            .filter { (proposal, _) ->
                iou(anchor, proposal.bbox) >= MIN_ANCHOR_IOU || containsCenter(proposal.bbox, anchor)
            }
            .maxByOrNull { it.second }
            ?.first
    }

    fun candidatesNearProposal(
        candidates: List<PoseCandidate>,
        proposal: PersonProposal,
    ): List<PoseCandidate> {
        return candidates.filter { candidate ->
            iou(candidate.bbox, proposal.bbox) >= MIN_CANDIDATE_IOU ||
                containsCenter(proposal.bbox, candidate.bbox) ||
                containsCenter(candidate.bbox, proposal.bbox)
        }
    }

    fun iou(a: PoseBoundingBox, b: PoseBoundingBox): Float {
        val left = max(a.minX, b.minX)
        val top = max(a.minY, b.minY)
        val right = min(a.maxX, b.maxX)
        val bottom = min(a.maxY, b.maxY)
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        val intersection = width * height
        val union = (a.area + b.area - intersection).coerceAtLeast(0.000001f)
        return (intersection / union).coerceIn(0f, 1f)
    }

    private fun containsCenter(container: PoseBoundingBox, target: PoseBoundingBox): Boolean {
        val x = (target.minX + target.maxX) / 2f
        val y = (target.minY + target.maxY) / 2f
        return container.contains(x, y)
    }
}

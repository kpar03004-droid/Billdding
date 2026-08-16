package kr.ddingtycoon.dtledger.watcher;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** 사이드바(스코어보드) 텍스트 수집. 대부분의 서버에서 골드 잔고가 여기 표시됨. */
public final class ScoreboardBalanceSource implements BalanceSource {

    @Override
    public String debugName() {
        return "scoreboard";
    }

    @Override
    public List<String> lines(MinecraftClient client) {
        List<String> out = new ArrayList<>();
        try {
            if (client.world == null) return out;
            Scoreboard sb = client.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return out;

            out.add(obj.getDisplayName().getString()); // 제목
            for (ScoreboardEntry entry : sb.getScoreboardEntries(obj)) {
                String owner = entry.owner();
                Team team = sb.getScoreHolderTeam(owner);
                String decorated = Team.decorateName(team, Text.literal(owner)).getString();
                out.add(decorated + " " + entry.value());
            }
        } catch (Throwable t) {
            // API 매핑 차이 등 — probe 단계에서 확인. 크래시 금지.
        }
        return out;
    }
}

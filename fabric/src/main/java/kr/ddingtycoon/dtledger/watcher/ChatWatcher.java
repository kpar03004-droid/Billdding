package kr.ddingtycoon.dtledger.watcher;

import kr.ddingtycoon.dtledger.core.CurrencyParser;
import kr.ddingtycoon.dtledger.core.TradeSignal;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.text.Text;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * 채팅/시스템 메시지를 CurrencyParser 로 파싱해 TradeSignal 발행.
 * 액션바(overlay=true)는 잔고 캡처용이므로 파싱 대상에서 제외.
 * "/플리마켓 금고" 결과(금고 잔액 스냅샷)는 거래가 아니라 재동기화로 처리.
 */
public final class ChatWatcher {
    private final CurrencyParser parser;
    private final Consumer<TradeSignal> signalSink;
    private final Consumer<String> actionBarSink;
    private final LongConsumer vaultSyncSink;

    public ChatWatcher(CurrencyParser parser, Consumer<TradeSignal> signalSink,
                       Consumer<String> actionBarSink, LongConsumer vaultSyncSink) {
        this.parser = parser;
        this.signalSink = signalSink;
        this.actionBarSink = actionBarSink;
        this.vaultSyncSink = vaultSyncSink;
    }

    public void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                actionBarSink.accept(message.getString());
            } else {
                handle(message);
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                handle(message));
    }

    private void handle(Text message) {
        String s = message.getString();
        // 금고 잔액 스냅샷이면 재동기화만 하고 거래 파싱은 건너뜀(레코드 미생성)
        long vaultBalance = CurrencyParser.parseVaultBalance(s);
        if (vaultBalance >= 0) {
            vaultSyncSink.accept(vaultBalance);
            return;
        }
        TradeSignal sig = parser.parse(s);
        if (sig != null) {
            signalSink.accept(sig);
        }
    }
}

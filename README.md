# KyberFood Impressora (Android)

App pequeno para a loja que não tem computador: imprime a comanda numa impressora térmica
Bluetooth e toca o som de pedido novo. O lojista continua usando o painel pelo navegador.

**O que faz.** Login, configuração guiada das permissões, impressora e som; os pedidos saem
sozinhos (o app pergunta ao servidor o que mudou, toca o alarme, busca a comanda pronta e
imprime); atende "Reimprimir" e o Fechamento do Dia do painel; e se atualiza pelo botão
"Procurar atualização".

## Publicação

Merge na `main` do monorepo **não** publica o app. Depois do merge:

```bash
npm run android:sync   # na raiz do monorepo
```

O comando espelha esta pasta para o repositório público `BrunoPaulinoF/kyberfood-android`,
cujo workflow (`.github/workflows/android-build.yml`, que mora aqui) roda os testes, builda,
**assina** e sobrescreve a Release fixa `android-latest` com `KyberFood-Impressora.apk` e
`latest.json`. O KyberFood lê essa release em `/api/android-app/download` e
`/api/android-app/latest-version` — é o que alimenta o botão de Integrações e o "Procurar
atualização" do app.

O repositório público precisa de quatro secrets: `KYBERFOOD_KEYSTORE_BASE64`,
`KYBERFOOD_KEYSTORE_PASSWORD`, `KYBERFOOD_SUPABASE_URL` e `KYBERFOOD_SUPABASE_ANON_KEY`. Sem
eles o build falha de propósito: instalador com a chave de teste não atualiza por cima do app
instalado. **A chave de assinatura não pode ser perdida nem trocada.**

A versão sobe sozinha a cada build (`version_code` publicado + 1; a primeira é a 100).

## Build

Precisa de JDK 17+, Gradle 8.9+ e Android SDK 35 (`ANDROID_HOME`).

```bash
export KYBERFOOD_SUPABASE_URL=...        # o mesmo NEXT_PUBLIC_SUPABASE_URL do web
export KYBERFOOD_SUPABASE_ANON_KEY=...   # a chave PÚBLICA (anon)
gradle :app:testDebugUnitTest :app:assembleRelease
# APK em app/build/outputs/apk/release/
```

Sem `KYBERFOOD_KEYSTORE_FILE` o APK sai com a chave de teste do Android: serve para provar,
não para distribuir (a versão assinada com a chave definitiva não instala por cima dela).

As regras do app estão em `.claude/rules/android-app.md`.

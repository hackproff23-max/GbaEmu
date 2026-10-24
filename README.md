# GBA Emu (Android)

Emulador de GBA com núcleo **mGBA (libretro)**, shaders GLSL importáveis, botões PNG em alta resolução e analógico virtual.

## Compilar sem PC (Termux + GitHub Actions)
1. Crie um repositório no GitHub e envie esta pasta:
   `git init && git add . && git commit -m init && git branch -M main && git remote add origin <URL> && git push -u origin main`
2. Aba **Actions** → workflow **Build APK** (roda sozinho no push). Baixe o artefato **GbaEmu-debug** (APK).
3. O workflow baixa o núcleo mGBA do buildbot do libretro e o inclui como `libmgba_core.so`.

## Uso
- Toque no botão ☰ (ou botão Back) para abrir o menu: ROM (.gba/.gb/.gbc/.zip), Shaders, Controles, Estados (3 slots), Acelerar, Reiniciar.
- **Shaders**: importe `.glsl` (RetroArch/libretro, arquivo único) ou pares `.vsh` + `.fsh`. Marque vários: a ordem marcada é a ordem das passadas. Escala interna 1x–6x. Uniforms reconhecidos por nome/tipo (MVPMatrix, TextureSize, InputSize, OutputSize, FrameCount, SourceSize, u_texelDelta…). Não há suporte a presets `.glslp` nem a texturas/`PassPrev` extras.
- **Botões PNG**: em Controles → Importar PNGs, escolha arquivos com estes nomes: `a.png b.png l.png r.png start.png select.png dpad.png stick_base.png stick_knob.png menu.png`. São usados em até 2048 px, sem recompressão. Pressionado = leve escurecimento/redução (sem PNG extra).
- **Layout**: Controles → Editar layout. Arraste cada botão; pince com 2 dedos para mudar o tamanho. Retrato e paisagem têm layouts separados.
- Gamepad físico. Tudo fica na pasta **EmuladorPro** da memória interna: `Configuracoes`, `SaveStates`, `Saves` (`.sav`), `Cheats`, `Capas` e `Controles`.
- **Cheats**: Configurações > Avançado liga o menu de cheats no jogo (GameShark / Action Replay / CodeBreaker do GBA).
- **Orientação de tela**: Configurações > Vídeo (Automático, Retrato, Paisagem).
- **Botões PNG**: Configurações > Controle > botões PNG (escolher cada um, exportar para editar em outro app).
- **Fundo**: Configurações > Avançado > Imagem de fundo (PNG, JPG ou GIF).
- **Cores e efeitos** (menu ☰ do jogo): Brilho, Contraste e Saturação (-100 a +100) e Efeito LCD (opacidade da grade entre os pixels, 0 a 100). Tudo começa em 0 (imagem original); só gasta desempenho quando algum valor sai de 0.
- **Orientação**: Configurações > Vídeo tem duas escolhas separadas, uma para o app (biblioteca e configurações) e outra para o jogo.

# PDF Reader para Android

Leitor de PDF minimalista para Android com core em C++ (MuPDF).

## Recursos

- **Core C++ com MuPDF**: renderização rápida via NDK
- **Modo Vertical**: scroll contínuo vertical entre páginas
- **Modo Livro**: paginação horizontal com swipe e tap zones
- **Bookmarks**: salva página, modo e timestamp automaticamente
- **Dark Mode**: inverte cores do PDF para leitura noturna
- **Minimalista**: UI clean, sem distrações

## Como compilar

### 1. Obter MuPDF

Baixe o MuPDF e compile para Android, ou use prebuilt:

```bash
git clone --recursive https://github.com/ArtifexSoftware/mupdf.git
# Siga as instruções de build do MuPDF para Android
# Coloque os headers em mupdf/include e libs em mupdf/libs/<abi>
```

### 2. Compilar o app

```bash
cd android
./gradlew assembleDebug
```

## Como portar o core

O core em `core/` é independente do Android. Para portar para outra plataforma:

1. Compile `core/src/pdf_engine.cpp` com MuPDF
2. Implemente bindings para sua plataforma (JavaScript, Python, etc.)
3. Use a API `PdfEngine` em C++

## Estrutura do C++

- `pdfcore::PdfEngine`: abre, renderiza e extrai texto de PDFs
- `pdfcore::RenderedPage`: bitmap em memória (RGBA)
- `pdfcore::PageInfo`: dimensões da página
- `setDarkMode(bool)`: aplica inversão de cores nos pixels

## JNI Bridge

`native-lib.cpp` expõe métodos estáticos para `PdfNative.kt`:
- Cria/destroi engine
- Abre/fecha documento
- Renderiza página para `android.graphics.Bitmap`
- Retorna contagem de páginas e título

## Próximos passos sugeridos

- [ ] Adicionar zoom pinch-to-zoom
- [ ] Busca de texto com highlight
- [ ] Índice/navegação por outline
- [ ] Cache de bitmaps em disco (LRU)
- [ ] Suporte a anotações

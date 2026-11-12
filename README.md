<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="">

# Liberte minha TV - por Bc

**Uma tela inicial rápida e sem anúncios para Google TV e Android TV, no estilo da Apple TV.**

[Baixar a última versão](https://github.com/matheusbcbg/liberte-minha-tv/releases/latest) · [Instalar](#instalar) · [Atualizar](#atualizar) · [Voltar ao original](#voltar-à-tela-original)

</div>

## O que você ganha

- **Sem anúncios.** Nenhuma fileira patrocinada, banner ou vídeo que começa sozinho.
- **Início no estilo Apple TV.** Um painel grande no alto, que muda conforme o app em foco. Embaixo, uma grade com todos os seus apps e os favoritos na primeira fileira. Relógio, data e temperatura sempre visíveis.
- **Organize do seu jeito.** Segure OK num app para movê-lo para o topo, criar pastas, editar o Início ou ocultar o app.
- **Continuar assistindo.** O que você parou na metade aparece com a imagem e o logotipo do filme ou da série, vindos do TMDB, em vez da imagem do app.
- **Explorar.** Filmes, séries e documentários do TMDB, com "Onde assistir" no Brasil: o que está nos seus apps, o que se aluga e o que se compra.
- **Quero assistir.** Segure OK em qualquer filme ou série, no Explorar ou no painel do Início, e escolha *Adicionar a Quero assistir*. A lista aparece no Explorar e no painel do Início.
- **Claro tv+ conferida.** O TMDB lista a Claro tv+ como assinatura em quase todo lançamento. Aqui cada título é conferido no catálogo da própria Claro: se vem num canal ou parceiro, aparece como "Claro tv+ · HBO"; se é aluguel, só em *Alugar*; se não está lá, não aparece.
- **Central de controle e Ajustes simples.** Imagem, som, rede, Bluetooth e entradas a um botão. Ajustes em duas colunas, com explicações curtas.
- **Seguro.** Sem root. Nada é desinstalado. Um botão traz de volta a tela original do Google. Se a tela nova falhar, o app volta sozinho para a versão anterior.
- **Atualiza sozinho.** O app confere uma vez por semana se saiu uma versão nova aqui no GitHub e avisa. Você instala com um OK.

## Instalar

Leva uns dez minutos. Você precisa de uma TV com Google TV ou Android TV (Android 8 ou mais novo) e de um computador na mesma rede Wi-Fi. O app vai na TV, não no celular.

1. **Na TV, ligue o modo desenvolvedor:** *Configurações* → *Sistema* → *Sobre* → aperte OK 7 vezes em *Versão do SO do Android TV*. Depois, em *Configurações* → *Sistema* → *Opções do desenvolvedor*, ligue a **Depuração USB**.
2. **Ache o IP da TV:** *Configurações* → *Rede e Internet* → a sua rede. Algo como `192.168.1.15`.
3. **Instale o adb no computador:**
   - Mac: `brew install android-platform-tools`
   - Windows: baixe as [Platform Tools](https://developer.android.com/tools/releases/platform-tools) e abra o terminal na pasta.
   - Linux: `sudo apt install adb`
4. **Baixe o `LiberteMinhaTV.apk`** da [última versão](https://github.com/matheusbcbg/liberte-minha-tv/releases/latest).
5. **Conecte e instale** (troque o IP pelo da sua TV):

   ```bash
   adb connect 192.168.1.15:5555
   adb install -r LiberteMinhaTV.apk
   ```

   Na primeira vez, a TV pergunta se aceita a depuração: marque *Sempre permitir* e confirme. Se a instalação parar, o Google Play Protect está perguntando na TV: escolha *Mais detalhes* → *Instalar mesmo assim*.
6. **Só em TV TCL**, rode também:

   ```bash
   adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
   ```

7. **Na TV**, abra o app *Liberte minha TV - por Bc* e escolha *Início* → *Liberte minha TV*. A TV pede mais uma vez para permitir a depuração: aceite.

### A chave do TMDB

Os filmes, as séries e as imagens vêm do [TMDB](https://www.themoviedb.org), que pede uma chave gratuita e pessoal:

1. Crie uma conta em themoviedb.org → *Configurações* → *API* e peça uma chave (uso pessoal).
2. Na TV: *Ajustes do Início* → *Chave do TMDB*. Aparece um QR code: leia com o celular e cole a chave.

Sem a chave, tudo funciona, menos o Explorar, as sugestões e as imagens dos filmes. A chave é sua: não compartilhe.

## Atualizar

O app procura uma versão nova aqui no GitHub uma vez por semana. Para conferir na hora, entre em *Ajustes do Início* → *Atualizações*. Quando houver uma, ele baixa, confere que veio deste repositório e foi assinada pela mesma chave, e o Android pergunta se pode instalar. Nada se perde: seus apps, a ordem, a lista e a chave do TMDB continuam.

## Voltar à tela original

*Liberte minha TV - por Bc* → *Início* → *Original*. A tela do Google volta como era, com os apps que o Liberte minha TV tinha desligado.

## Créditos

O Liberte minha TV é uma versão modificada do [Free My TV](https://github.com/simbasjungles/free-my-tv), de código aberto e licença MIT. Esta versão traz o Início no estilo Apple TV, os Ajustes novos, o Explorar fixado no Brasil, a lista Quero assistir e a conferência da Claro tv+. A licença está em [LICENSE](LICENSE).

Os dados de filmes e séries vêm do TMDB. Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.

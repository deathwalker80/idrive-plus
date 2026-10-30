// prova sul PC della logica dei plugin (scaricamento, caselle, titoli) con un finto framework bmw
#include "../ponte_plugin.cpp"
extern "C" int printf(const char*, ...);
extern "C" int* __errno_location(void);
extern "C" int* __get_errno_ptr(void) { return __errno_location(); }
extern "C" void bsCrea(void* self, const char* s) __asm__("_ZN3bmw11BasicStringIcEC1EPKc");
extern "C" void bsCrea(void* self, const char* s) { *(const char**)self = s; }
extern "C" void bsDistruggi(void* self) __asm__("_ZN3bmw11BasicStringIcED1Ev");
extern "C" void bsDistruggi(void*) {}
namespace bmw {
BufferStream::~BufferStream() {}
Image::Image(const unsigned char* d, u32 n) { memcpy(m_storage, &n, 4); memcpy(m_storage + 4, d + 1, 3); }
PluginApp::PluginApp(IDispatcher&, BasicString<char>, u32, u32, RHMIInterfaceVersion, const Bundle&, const Bundle&) {}
void PluginApp::post(Runnable*, u32) {}
void PluginApp::updateModel(u32 id, const char* t) { printf("  updateModel(%u, testo \"%s\")\n", id, t); }
void PluginApp::updateModel(u32 id, const Image& im) { u32 n; memcpy(&n, &im, 4); printf("  updateModel(%u, immagine %u byte, %.3s)\n", id, n, (const char*)&im + 4); }
bool PluginApp::onInit() { return true; }
void PluginApp::addHandler(u32 id, const DelegatoAzione& d) { printf("  addHandler(%u, oggetto %lu)\n", id, (unsigned long)d.oggetto); }
void PluginApp::onFinalize() {}
}
int main() {
    static unsigned char mem[0x200];
    g_app = (bmw::PluginApp*)mem;
    preparaCaselle();
#if defined(PLUGIN_NOTIZIE) && SCHEDA_LATERALE
    printf("== appOnInit (registrazione delle righe)\n");
    for (u32 i = 0; i < kMaxNotizie; ++i) { bmw::DelegatoAzione d; d.oggetto = (void*)(unsigned long)i; d.funzione = &rigaScelta; g_app->addHandler(kAzioneScelta0 + i, d); }
#endif
    printf("== schermata di attesa\n"); schermataAttesa(); svuotaCaselle();
    g_telefono = ip4(127, 0, 0, 1);
    u32 t = trovaTelefono();
    printf("== trovaTelefono -> %s\n", t ? "127.0.0.1" : "NIENTE");
    int codice = 0;
    void* ponte = scarica(t, "/ponte", &codice);
    printf("== /ponte codice %d: %s", codice, ponte ? testo(ponte) : "(nulla)\n");
    bool ok = aggiornaDati(t, testo(ponte));
    printf("== aggiornaDati -> %d, epoch fatto %u\n", ok, g_epochFatto);
    svuotaCaselle();
#if defined(PLUGIN_NOTIZIE) && SCHEDA_LATERALE
    printf("== riga 3 selezionata\n");
    static char finto[64];
    rigaScelta((void*)2, *(const bmw::Parcel*)finto);
    printf("== di nuovo la riga 3 (niente)\n");
    rigaScelta((void*)2, *(const bmw::Parcel*)finto);
#endif
    printf("== secondo giro con lo stesso epoch (non deve scaricare niente)\n");
    aggiornaDati(t, testo(ponte)); svuotaCaselle();
    void* no = scarica(t, "/notizia/9.png", &codice);
    printf("== /notizia/9.png -> %p codice %d\n", no, codice);
    return 0;
}

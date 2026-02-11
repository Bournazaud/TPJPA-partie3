package pharmacie.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import pharmacie.dao.MedicamentRepository;
import pharmacie.entity.Commande;
import pharmacie.entity.Ligne;
import pharmacie.entity.Medicament;

@SpringBootTest
@Transactional // Annule les modifications en BDD après chaque test
class CommandeServiceTest {

    @Autowired
    private CommandeService service;

    @Autowired
    private MedicamentRepository medicamentDao;

    // --- Tests ajouterLigne ---

    @Test
    void ajouterLigne_CasNominal_NouveauMedicament() {
        // Commande 99998 est en cours (voir test_data.sql)
        // Medicament 93 est dispo, stock 100, commandé 0
        Ligne l = service.ajouterLigne(99998, 93, 10);

        assertNotNull(l.getId());
        assertEquals(10, l.getQuantite());

        // Vérifier que le compteur 'unitesCommandees' du médicament a augmenté
        Medicament m = medicamentDao.findById(93).orElseThrow();
        assertEquals(10, m.getUnitesCommandees());
    }

    @Test
    void ajouterLigne_CasNominal_MedicamentExistant() {
        // Commande 99998 contient déjà le medicament 98 avec qté 16
        // On ajoute 2 unités
        Ligne l = service.ajouterLigne(99998, 98, 2);

        // La quantité totale doit être 16 + 2 = 18
        assertEquals(18, l.getQuantite());

        // Vérif mise à jour entité Medicament (UnitesCommandees était 20 dans test_data.sql -> +2 = 22)
        Medicament m = medicamentDao.findById(98).orElseThrow();
        assertEquals(22, m.getUnitesCommandees());
    }

    @Test
    void ajouterLigne_Erreur_MedicamentIndisponible() {
        // Medicament 97 est indisponible (TRUE)
        assertThrows(IllegalStateException.class, () -> {
            service.ajouterLigne(99998, 97, 1);
        }, "Devrait échouer car médicament indisponible");
    }

    @Test
    void ajouterLigne_Erreur_CommandeEnvoyee() {
        // Commande 99999 est déjà envoyée
        assertThrows(IllegalStateException.class, () -> {
            service.ajouterLigne(99999, 93, 1);
        }, "Devrait échouer car commande déjà envoyée");
    }

    @Test
    void ajouterLigne_Erreur_StockInsuffisant() {
        // Medicament 98 : Stock 26, Commandées 20. Reste théorique dispo : 6.
        // On essaie d'en commander 10 -> Erreur
        assertThrows(IllegalStateException.class, () -> {
            service.ajouterLigne(99998, 98, 10);
        }, "Devrait échouer par manque de stock");
    }

    // --- Tests supprimerLigne ---

    // N'oublie pas cet attribut en haut de ta classe de test !
    @Autowired
    private jakarta.persistence.EntityManager entityManager;
    @Test
    void supprimerLigne_CasNominal() {
        // 1. Préparation : On récupère la commande de test 99998
        Commande c = service.getCommande(99998);
        Ligne ligneASupprimer = c.getLignes().get(0);
        int quantiteLigne = ligneASupprimer.getQuantite();
        int medRef = ligneASupprimer.getMedicament().getReference();

        // On mémorise l'état avant suppression
        Medicament mAvant = medicamentDao.findById(medRef).orElseThrow();
        int commandeesAvant = mAvant.getUnitesCommandees();

        // 2. Action : On appelle le SERVICE (c'est lui qui utilise ligneDao)
        service.supprimerLigne(ligneASupprimer.getId());

        // --- GESTION DU CACHE DE TEST ---
        entityManager.flush(); // Force l'envoi de la suppression à la BDD
        entityManager.clear(); // Vide la mémoire pour forcer la relecture
        // --------------------------------

        // 3. Vérifications
        // On recharge la commande depuis la base "propre"
        c = service.getCommande(99998);

        // La liste doit être vide
        assertTrue(c.getLignes().isEmpty(), "La liste des lignes devrait être vide après suppression");

        // Le compteur de médicament doit avoir diminué
        Medicament mApres = medicamentDao.findById(medRef).orElseThrow();
        assertEquals(commandeesAvant - quantiteLigne, mApres.getUnitesCommandees());
    }

    @Test
    void enregistreExpedition_CasNominal() {
        // Commande 99998 (contient med 98 qté 16)
        // Med 98 : Stock 26, Commandées 20

        Commande c = service.enregistreExpedition(99998);

        // 1. Date expédition renseignée
        assertNotNull(c.getEnvoyeele());
        assertEquals(java.time.LocalDate.now(), c.getEnvoyeele());

        // 2. Vérification déstockage Medicament 98
        Medicament m = medicamentDao.findById(98).orElseThrow();

        // Stock doit baisser de 16 (26 - 16 = 10)
        assertEquals(10, m.getUnitesEnStock(), "Stock physique décrémenté");

        // Commandées doit baisser de 16 (20 - 16 = 4)
        assertEquals(4, m.getUnitesCommandees(), "Stock réservé libéré");
    }

    @Test
    void enregistreExpedition_Erreur_DejaEnvoyee() {
        // Commande 99999 déjà envoyée
        assertThrows(IllegalStateException.class, () -> {
            service.enregistreExpedition(99999);
        });
    }
}

package pharmacie.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import pharmacie.dao.CommandeRepository;
import pharmacie.dao.DispensaireRepository;
import pharmacie.dao.LigneRepository;
import pharmacie.dao.MedicamentRepository;
import pharmacie.entity.Commande;
import pharmacie.entity.Ligne;
import pharmacie.entity.Medicament;

@Slf4j
@Service
@Validated
public class CommandeService {
    private final CommandeRepository commandeDao;
    private final DispensaireRepository dispensaireDao;
    private final LigneRepository ligneDao;
    private final MedicamentRepository medicamentDao;

    public CommandeService(CommandeRepository commandeDao, DispensaireRepository dispensaireDao, LigneRepository ligneDao, MedicamentRepository medicamentDao) {
        this.commandeDao = commandeDao;
        this.dispensaireDao = dispensaireDao;
        this.ligneDao = ligneDao;
        this.medicamentDao = medicamentDao;
    }

    @Transactional
    public Commande creerCommande(@NonNull String dispensaireCode) {
        log.info("Service : Création d'une commande pour {}", dispensaireCode);
        var dispensaire = dispensaireDao.findById(dispensaireCode).orElseThrow();
        var nouvelleCommande = new Commande(dispensaire);
        nouvelleCommande.setAdresseLivraison(dispensaire.getAdresse());
        var nbArticles = dispensaireDao.nombreArticlesCommandesPar(dispensaireCode);
        if (nbArticles > 100) {
            nouvelleCommande.setRemise(new BigDecimal("0.15"));
        }
        commandeDao.save(nouvelleCommande);
        return nouvelleCommande;
    }

    @Transactional
    public Ligne ajouterLigne(int commandeNum, int medicamentRef, @Positive int quantite) {
        // 1. Récupération des entités
        Commande commande = commandeDao.findById(commandeNum).orElseThrow();
        Medicament medicament = medicamentDao.findById(medicamentRef).orElseThrow();

        // 2. Règles métier
        // La commande ne doit pas être déjà envoyée
        if (commande.getEnvoyeele() != null) {
            throw new IllegalStateException("Impossible de modifier une commande déjà expédiée.");
        }
        // Le médicament ne doit pas être indisponible
        if (medicament.isIndisponible()) {
            throw new IllegalStateException("Le médicament " + medicament.getNom() + " est indisponible.");
        }
        // Vérification du stock (Stock physique >= Stock déjà réservé + nouvelle quantité)
        if (medicament.getUnitesEnStock() < (medicament.getUnitesCommandees() + quantite)) {
            throw new IllegalStateException("Stock insuffisant pour " + medicament.getNom());
        }

        // 3. Mise à jour ou création de la ligne
        Optional<Ligne> ligneExistante = ligneDao.findByCommandeAndMedicament(commande, medicament);
        Ligne ligne;

        if (ligneExistante.isPresent()) {
            // Mise à jour de la quantité si le médicament est déjà dans la commande
            ligne = ligneExistante.get();
            ligne.setQuantite(ligne.getQuantite() + quantite);
        } else {
            // Création d'une nouvelle ligne
            ligne = new Ligne();
            ligne.setCommande(commande);
            ligne.setMedicament(medicament);
            ligne.setQuantite(quantite);
        }

        // 4. Mise à jour du compteur "Unités commandées" (réservation)
        medicament.setUnitesCommandees(medicament.getUnitesCommandees() + quantite);

        // Sauvegarde explicite de la ligne
        return ligneDao.save(ligne);
    }

    @Transactional
    public void supprimerLigne(int id) {
        // 1. Récupération
        Ligne ligne = ligneDao.findById(id).orElseThrow();
        Commande commande = ligne.getCommande();
        Medicament medicament = ligne.getMedicament();

        // 2. Règle métier : commande non expédiée
        if (commande.getEnvoyeele() != null) {
            throw new IllegalStateException("Impossible de modifier une commande déjà expédiée.");
        }

        // 3. Mise à jour du compteur "Unités commandées"
        medicament.setUnitesCommandees(medicament.getUnitesCommandees() - ligne.getQuantite());

        // --- CORRECTION CRUCIALE ICI ---
        // On coupe le lien Java : on enlève la ligne de la liste de la commande
        // Sinon Hibernate croit que la ligne doit toujours exister car la commande la contient encore
        commande.getLignes().remove(ligne);
        // -------------------------------

        // 4. Suppression
        ligneDao.delete(ligne);
    }

    @Transactional
    public Commande enregistreExpedition(int commandeNum) {
        // 1. Récupération
        Commande commande = commandeDao.findById(commandeNum).orElseThrow();

        // 2. Règle métier : non déjà expédiée
        if (commande.getEnvoyeele() != null) {
            throw new IllegalStateException("Cette commande est déjà expédiée.");
        }

        // 3. Mise à jour de la date
        commande.setEnvoyeele(LocalDate.now());

        // 4. Mise à jour des stocks pour chaque ligne
        for (Ligne ligne : commande.getLignes()) {
            Medicament m = ligne.getMedicament();
            int qte = ligne.getQuantite();

            // On décrémente le stock physique car les produits sortent de l'entrepôt
            m.setUnitesEnStock(m.getUnitesEnStock() - qte);
            // On décrémente le stock "réservé" car la commande n'est plus "en attente"
            m.setUnitesCommandees(m.getUnitesCommandees() - qte);

            // Note: pas besoin de m.save(), @Transactional gère l'update via JPA
        }

        return commandeDao.save(commande);
    }

    @Transactional
    public Commande getCommande(int commandeNum) {
        return commandeDao.findById(commandeNum).orElseThrow();
    }

    @Transactional
    public List<Commande> getCommandeEnCoursPour(String dispensaireCode) {
        return commandeDao.commandesEnCoursPour(dispensaireCode);
    }
}
